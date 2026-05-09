package com.repomind.backend.service.ai.dto;

public record AiGenerationResponse(
        String text,
        AiUsage usage,
        String model
) {
}
