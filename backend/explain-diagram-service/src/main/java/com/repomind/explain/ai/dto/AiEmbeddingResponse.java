package com.repomind.explain.ai.dto;

import java.util.List;

public record AiEmbeddingResponse(
        List<Double> embedding,
        AiUsage usage,
        String model
) {
}
