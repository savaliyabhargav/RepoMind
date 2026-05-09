package com.repomind.backend.service.analysis.dto;

import com.repomind.backend.domain.analysis.AnalysisStage;

import java.time.Instant;
import java.util.UUID;

public record AnalysisStageResponse(
        UUID id,
        UUID analysisId,
        Integer stageNumber,
        String stageName,
        String status,
        String result,
        Integer tokensUsed,
        Instant startedAt,
        Instant completedAt
) {
    public static AnalysisStageResponse from(AnalysisStage stage) {
        return new AnalysisStageResponse(
                stage.getId(),
                stage.getAnalysis().getId(),
                stage.getStageNumber(),
                stage.getStageName(),
                stage.getStatus(),
                stage.getResult(),
                stage.getTokensUsed(),
                stage.getStartedAt(),
                stage.getCompletedAt()
        );
    }
}

