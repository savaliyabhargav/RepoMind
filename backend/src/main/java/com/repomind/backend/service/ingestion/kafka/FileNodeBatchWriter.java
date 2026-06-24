package com.repomind.backend.service.ingestion.kafka;

import com.repomind.backend.domain.repo.FileNode;
import com.repomind.backend.domain.repo.FileNodeRepository;
import com.repomind.backend.domain.repo.RepoRepository;
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
    private final RepoRepository repoRepository;
    private final RedisTemplate<String, String> redisTemplate;

    // ConcurrentLinkedQueue is thread-safe — multiple Kafka listener threads
    // can add to it simultaneously without corrupting data
    private final ConcurrentLinkedQueue<FileNode> buffer = new ConcurrentLinkedQueue<>();

    // Step 1 — Kafka listener adds incoming file nodes to the in-memory buffer
    // Does NOT write to DB here — just collects them
    @KafkaListener(
            topics = "repo-filenodes-raw",
            groupId = "filenode-writers",
            concurrency = "3"
    )
    public void receiveFileNode(FileNodeMessage message, Acknowledgment ack) {
        // getReferenceById returns a JPA proxy — no DB hit, just sets the FK
        // This is the correct way to set a parent reference without loading it
        FileNode node = FileNode.builder()
                .repo(repoRepository.getReferenceById(message.repoId()))
                .path(message.path())
                .name(message.name())
                .type(message.type())
                .depth(message.depth())
                .sizeBytes(message.sizeBytes())
                .isInScope(true)
                .build();

        buffer.add(node);
        // Acknowledge immediately — we have the data in memory, that is enough
        // If server crashes here, the worst case is re-processing on restart
        ack.acknowledge();
    }

    // Step 2 — Every 2 seconds, drain the buffer and do ONE bulk insert to DB
    // This runs on a separate scheduler thread, not the Kafka listener thread
    @Scheduled(fixedDelay = 2000)
    public void flushBuffer() {
        if (buffer.isEmpty()) return;

        // Drain all items currently in buffer into a local list
        // New items arriving during this drain will go into the next flush
        List<FileNode> toWrite = new ArrayList<>();
        FileNode node;
        while ((node = buffer.poll()) != null) {
            toWrite.add(node);
        }

        if (toWrite.isEmpty()) return;

        log.info("Batch writing {} file nodes to DB", toWrite.size());

        // ONE bulk INSERT for everything collected in the last 2 seconds
        // Much faster than N individual inserts
        fileNodeRepository.saveAll(toWrite);

        // After DB write is confirmed, delete the Redis cache for these repos
        // From now on the tree endpoint will read from DB, not Redis
        Set<UUID> writtenRepoIds = toWrite.stream()
                .map(n -> n.getRepo().getId())
                .collect(Collectors.toSet());

        writtenRepoIds.forEach(repoId -> {
            redisTemplate.delete("repo:tree:" + repoId);
            log.info("Cleared Redis cache for repoId={} (now in DB)", repoId);
        });
    }
}
