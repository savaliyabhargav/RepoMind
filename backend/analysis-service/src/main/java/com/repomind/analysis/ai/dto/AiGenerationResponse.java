package com.repomind.analysis.ai.dto;

public record AiGenerationResponse(
        String text,
        AiUsage usage,
        String model
) {
}
