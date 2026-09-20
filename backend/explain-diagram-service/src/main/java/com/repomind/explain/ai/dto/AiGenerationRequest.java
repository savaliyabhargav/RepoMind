package com.repomind.explain.ai.dto;

public record AiGenerationRequest(
        String provider,
        String model,
        String systemPrompt,
        String userPrompt,
        double temperature,
        int maxTokens
) {
}
