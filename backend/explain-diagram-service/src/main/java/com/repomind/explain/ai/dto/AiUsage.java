package com.repomind.explain.ai.dto;

public record AiUsage(int inputTokens, int outputTokens) {
    public int total() {
        return Math.max(0, inputTokens) + Math.max(0, outputTokens);
    }
}
