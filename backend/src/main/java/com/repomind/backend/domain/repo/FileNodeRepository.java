package com.repomind.backend.domain.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FileNodeRepository extends JpaRepository<FileNode, UUID> {

    List<FileNode> findByCanonicalRepoId(UUID canonicalRepoId);

    List<FileNode> findByCanonicalRepoIdOrderByPathAsc(UUID canonicalRepoId);

    List<FileNode> findByCanonicalRepoIdAndIsInScopeTrue(UUID canonicalRepoId);

    List<FileNode> findByCanonicalRepoIdAndType(UUID canonicalRepoId, String type);

    List<FileNode> findByCanonicalRepoIdAndIsInScopeTrueAndTypeAndSizeBytesLessThan(
            UUID canonicalRepoId, String type, long maxSize);

    @Modifying
    @Query("UPDATE FileNode f SET f.roleSummary = :summary WHERE f.id = :id")
    void updateRoleSummary(@Param("id") UUID id, @Param("summary") String summary);

    @Modifying
    @Query("UPDATE FileNode f SET f.embeddingId = :embeddingId WHERE f.id = :id")
    void updateEmbeddingId(@Param("id") UUID id, @Param("embeddingId") String embeddingId);
}
