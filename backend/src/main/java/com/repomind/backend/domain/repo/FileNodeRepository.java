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

    List<FileNode> findByRepoId(UUID repoId);

    List<FileNode> findByRepoIdOrderByPathAsc(UUID repoId);

    List<FileNode> findByRepoIdAndIsInScopeTrue(UUID repoId);

    List<FileNode> findByRepoIdAndType(UUID repoId, String type);

    // Used for Stage 6: Finding files to annotate that aren't too large
    List<FileNode> findByRepoIdAndIsInScopeTrueAndTypeAndSizeBytesLessThan(
            UUID repoId, String type, long maxSize);

    // Specialized update for AI summaries
    @Modifying
    @Query("UPDATE FileNode f SET f.roleSummary = :summary WHERE f.id = :id")
    void updateRoleSummary(@Param("id") UUID id, @Param("summary") String summary);

    // Specialized update for Qdrant embedding IDs
    @Modifying
    @Query("UPDATE FileNode f SET f.embeddingId = :embeddingId WHERE f.id = :id")
    void updateEmbeddingId(@Param("id") UUID id, @Param("embeddingId") String embeddingId);
}
