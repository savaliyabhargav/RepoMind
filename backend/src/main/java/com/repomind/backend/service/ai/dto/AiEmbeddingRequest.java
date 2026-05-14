package com.repomind.backend.service.ai.dto;

public record AiEmbeddingRequest(
        String provider,
        String model,
        String input,
        String inputType
) {
}
