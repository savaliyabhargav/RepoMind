package com.repomind.backend.service.ai.dto;

import java.util.List;

public record AiEmbeddingResponse(
        List<Double> embedding,
        AiUsage usage,
        String model
) {
}
