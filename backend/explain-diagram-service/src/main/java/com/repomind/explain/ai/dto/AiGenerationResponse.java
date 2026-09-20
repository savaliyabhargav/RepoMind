package com.repomind.explain.ai.dto;

public record AiGenerationResponse(
        String text,
        AiUsage usage,
        String model
) {
}
