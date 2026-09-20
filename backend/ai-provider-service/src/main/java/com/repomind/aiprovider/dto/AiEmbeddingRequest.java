package com.repomind.aiprovider.dto;

public record AiEmbeddingRequest(
        String provider,
        String model,
        String input,
        String inputType
) {
}
