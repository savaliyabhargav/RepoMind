package com.repomind.backend.service.ingestion.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RepoIngestionProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Called by the API thread — takes about 5ms, then the thread is free
    public void submitIngestion(UUID repoId, String url, UUID userId) {
        IngestRequestMessage message = new IngestRequestMessage(
                repoId,
                url,
                userId,
                System.currentTimeMillis()
        );

        // Key = repoId ensures same repo always goes to same partition
        // This prevents two workers picking up the same repo simultaneously
        kafkaTemplate.send("repo-ingest-requests", repoId.toString(), message);
        log.info("Queued ingestion for repoId={} url={}", repoId, url);
    }
}
