package com.repomind.analysis.ai.dto;

import java.util.List;

public record AiEmbeddingResponse(
        List<Double> embedding,
        AiUsage usage,
        String model
) {
}
