package com.repomind.backend.service.ingestion.kafka;

import java.util.UUID;

// This is what the IngestionWorker publishes after getting the tree from GitHub.
// The FileNodeBatchWriter reads these and bulk-inserts them into the DB every 2 seconds.
public record FileNodeMessage(
        UUID repoId,
        String path,
        String name,
        String type,
        Integer depth,
        Long sizeBytes
) {}
