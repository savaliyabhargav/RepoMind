package com.repomind.backend.service.ingestion.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    // Max nodes drained per flush cycle — keeps each DB transaction under ~300ms
    // even for huge repos. Remaining nodes stay in the buffer for the next cycle (2s later).
    private static final int FLUSH_BATCH_SIZE = 1000;

    // ON CONFLICT DO NOTHING makes writes idempotent: Kafka redeliveries and two
    // workers racing on the same canonical repo would otherwise violate
    // uq_file_nodes_canonical_path and fail the entire batch.
    private static final String UPSERT_SQL = """
            INSERT INTO file_nodes (id, canonical_repo_id, path, name, type, depth, size_bytes, is_in_scope)
            VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
            ON CONFLICT (canonical_repo_id, path) DO NOTHING
            """;

    private record PendingNode(FileNodeMessage message, Acknowledgment ack) {}

    private final ConcurrentLinkedQueue<PendingNode> buffer = new ConcurrentLinkedQueue<>();

    @KafkaListener(
            topics = "repo-filenodes-raw",
            groupId = "filenode-writers",
            concurrency = "3"
    )
    public void receiveFileNode(FileNodeMessage message, Acknowledgment ack) {
        // Ack is deferred until after flushBuffer() confirms the DB write succeeds
        buffer.add(new PendingNode(message, ack));
    }

    @Scheduled(fixedDelay = 2000)
    public void flushBuffer() {
        if (buffer.isEmpty()) return;

        List<PendingNode> pending = new ArrayList<>(FLUSH_BATCH_SIZE);
        PendingNode item;
        while (pending.size() < FLUSH_BATCH_SIZE && (item = buffer.poll()) != null) {
            pending.add(item);
        }

        if (pending.isEmpty()) return;

        log.info("Batch writing {} file nodes to DB", pending.size());

        try {
            jdbcTemplate.batchUpdate(UPSERT_SQL, pending, FLUSH_BATCH_SIZE, (ps, p) -> {
                FileNodeMessage m = p.message();
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, m.canonicalRepoId());
                ps.setString(3, m.path());
                ps.setString(4, m.name());
                ps.setString(5, m.type());
                ps.setObject(6, m.depth());
                ps.setObject(7, m.sizeBytes());
            });
        } catch (Exception ex) {
            // Transient DB failure: put the nodes back so the next cycle retries them.
            // Without this, a single failed flush silently loses up to 1000 file nodes.
            log.error("Batch write of {} file nodes failed — re-queueing for next flush cycle",
                    pending.size(), ex);
            buffer.addAll(pending);
            return;
        }

        // Ack only after confirmed DB write — Kafka redelivers if the insert fails
        pending.forEach(p -> p.ack().acknowledge());

        // Clear Redis caches for the user repos whose file nodes are now in DB
        Set<UUID> userRepoIds = pending.stream()
                .map(p -> p.message().userRepoId())
                .collect(Collectors.toSet());

        userRepoIds.forEach(repoId -> {
            redisTemplate.delete("repo:tree:" + repoId);
            log.info("Cleared Redis cache for repoId={} (file nodes now in DB)", repoId);
        });
    }
}
