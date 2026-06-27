package com.repomind.backend.service.ingestion.kafka;

import java.util.UUID;

// canonicalRepoId — points to the shared canonical_repos row (used for DB write)
// userRepoId     — the requesting user's repos row (used for Redis cache invalidation)
public record FileNodeMessage(
        UUID canonicalRepoId,
        UUID userRepoId,
        String path,
        String name,
        String type,
        Integer depth,
        Long sizeBytes
) {}
