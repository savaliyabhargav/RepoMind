package com.repomind.aiprovider.dto;

public record AiGenerationResponse(
        String text,
        AiUsage usage,
        String model
) {
}
