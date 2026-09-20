package com.repomind.explain.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileExplainCacheRepository extends JpaRepository<FileExplainCache, UUID> {

    Optional<FileExplainCache> findByCanonicalRepoIdAndPathAndContentHash(
            UUID canonicalRepoId, String path, String contentHash);
}
