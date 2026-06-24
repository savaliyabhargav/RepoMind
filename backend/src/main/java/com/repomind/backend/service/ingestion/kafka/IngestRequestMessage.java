package com.repomind.backend.service.ingestion.kafka;

import java.util.UUID;

// This is the message the API thread writes to Kafka.
// Contains everything the IngestionWorker needs to do its job.
public record IngestRequestMessage(
        UUID repoId,
        String url,
        UUID userId,
        long submittedAt
) {}
