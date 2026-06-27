package com.repomind.backend.service.ingestion.kafka;

import com.repomind.backend.domain.repo.CanonicalRepo;
import com.repomind.backend.domain.repo.CanonicalRepoRepository;
import com.repomind.backend.domain.repo.FileNode;
import com.repomind.backend.domain.repo.FileNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class FileNodeBatchWriter {

    private final FileNodeRepository fileNodeRepository;
    private final CanonicalRepoRepository canonicalRepoRepository;
    private final RedisTemplate<String, String> redisTemplate;

    // Max nodes drained per flush cycle — keeps each DB transaction under ~300ms
    // even for huge repos. Remaining nodes stay in the buffer for the next cycle (2s later).
    private static final int FLUSH_BATCH_SIZE = 1000;

    private record PendingNode(FileNode node, UUID userRepoId, Acknowledgment ack) {}

    private final ConcurrentLinkedQueue<PendingNode> buffer = new ConcurrentLinkedQueue<>();

    @KafkaListener(
            topics = "repo-filenodes-raw",
            groupId = "filenode-writers",
            concurrency = "3"
    )
    public void receiveFileNode(FileNodeMessage message, Acknowledgment ack) {
        // getReferenceById returns a JPA proxy — no DB hit, just sets the FK
        CanonicalRepo canonicalRef = canonicalRepoRepository.getReferenceById(message.canonicalRepoId());

        FileNode node = FileNode.builder()
                .canonicalRepo(canonicalRef)
                .path(message.path())
                .name(message.name())
                .type(message.type())
                .depth(message.depth())
                .sizeBytes(message.sizeBytes())
                .isInScope(true)
                .build();

        // Ack is deferred until after flushBuffer() confirms the DB write succeeds
        buffer.add(new PendingNode(node, message.userRepoId(), ack));
    }

    @Scheduled(fixedDelay = 2000)
    public void flushBuffer() {
        if (buffer.isEmpty()) return;

        List<PendingNode> pending = new ArrayList<>(FLUSH_BATCH_SIZE);
        PendingNode item;
        int drained = 0;
        while (drained < FLUSH_BATCH_SIZE && (item = buffer.poll()) != null) {
            pending.add(item);
            drained++;
        }

        if (pending.isEmpty()) return;

        List<FileNode> nodes = pending.stream().map(PendingNode::node).toList();
        log.info("Batch writing {} file nodes to DB", nodes.size());

        fileNodeRepository.saveAll(nodes);

        // Ack only after confirmed DB write — Kafka redelivers if saveAll() throws
        pending.forEach(p -> p.ack().acknowledge());

        // Clear Redis caches for the user repos whose file nodes are now in DB
        Set<UUID> userRepoIds = pending.stream()
                .map(PendingNode::userRepoId)
                .collect(Collectors.toSet());

        userRepoIds.forEach(repoId -> {
            redisTemplate.delete("repo:tree:" + repoId);
            log.info("Cleared Redis cache for repoId={} (file nodes now in DB)", repoId);
        });
    }
}
