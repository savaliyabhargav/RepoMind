package com.repomind.backend.domain.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AnalysisStageRepository extends JpaRepository<AnalysisStage, UUID> {

    List<AnalysisStage> findByAnalysisIdOrderByStageNumberAsc(UUID analysisId);

    // Used to mark a stage as started
    @Modifying
    @Query("UPDATE AnalysisStage s SET s.status = :status, s.startedAt = :startedAt WHERE s.id = :id")
    void updateStageStarted(@Param("id") UUID id, @Param("status") String status, @Param("startedAt") Instant startedAt);

    // Used to save the final JSON result for a specific stage
    @Modifying
    @Query("UPDATE AnalysisStage s SET s.status = :status, s.result = :result, s.completedAt = :completedAt, s.tokensUsed = :tokens WHERE s.id = :id")
    void updateStageCompleted(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("result") String result,
            @Param("completedAt") Instant completedAt,
            @Param("tokens") Integer tokens
    );
}