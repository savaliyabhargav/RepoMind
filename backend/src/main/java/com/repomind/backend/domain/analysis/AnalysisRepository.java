package com.repomind.backend.domain.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AnalysisRepository extends JpaRepository<Analysis, UUID> {

    // Used to find all historical analyses for a specific repository
    List<Analysis> findByRepoIdOrderByCreatedAtDesc(UUID repoId);

    // Find the latest active analysis for a user and repo
    List<Analysis> findByRepoIdAndUserIdOrderByCreatedAtDesc(UUID repoId, UUID userId);
}