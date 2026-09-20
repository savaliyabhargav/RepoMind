package com.repomind.explain.ai.dto;

public record AiEmbeddingRequest(
        String provider,
        String model,
        String input,
        String inputType
) {
}
