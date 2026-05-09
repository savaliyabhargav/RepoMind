package com.repomind.backend.service.analysis.dto;

import com.repomind.backend.domain.analysis.Analysis;

import java.time.Instant;
import java.util.UUID;

public record AnalysisResponse(
        UUID id,
        UUID repoId,
        UUID userId,
        String aiProvider,
        String status,
        Integer currentStage,
        String result,
        String errorMsg,
        Integer tokensUsed,
        Instant createdAt,
        Instant completedAt
) {
    public static AnalysisResponse from(Analysis analysis) {
        return new AnalysisResponse(
                analysis.getId(),
                analysis.getRepo().getId(),
                analysis.getUser().getId(),
                analysis.getAiProvider(),
                analysis.getStatus(),
                analysis.getCurrentStage(),
                analysis.getResult(),
                analysis.getErrorMsg(),
                analysis.getTokensUsed(),
                analysis.getCreatedAt(),
                analysis.getCompletedAt()
        );
    }
}

