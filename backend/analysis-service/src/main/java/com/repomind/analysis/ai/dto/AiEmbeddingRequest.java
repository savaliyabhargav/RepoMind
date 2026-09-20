package com.repomind.analysis.ai.dto;

public record AiEmbeddingRequest(
        String provider,
        String model,
        String input,
        String inputType
) {
}
