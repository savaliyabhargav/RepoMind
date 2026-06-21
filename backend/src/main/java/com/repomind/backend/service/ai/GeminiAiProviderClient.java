package com.repomind.backend.service.ai;

import com.repomind.backend.service.ai.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;

@Service
public class GeminiAiProviderClient implements AiProviderClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiProviderClient.class);

    private final WebClient webClient;
    private final String apiKey;

    public GeminiAiProviderClient(
            WebClient.Builder webClientBuilder,
            @Value("${app.ai.gemini.api-key:}") String apiKey
    ) {
        this.webClient = webClientBuilder.baseUrl("https://generativelanguage.googleapis.com").build();
        this.apiKey = apiKey;
    }

    @Override
    public boolean supports(String provider) {
        return provider != null && provider.toUpperCase(Locale.ROOT).contains("GEMINI");
    }

    @Override
    public AiGenerationResponse generate(AiGenerationRequest request) {
        requireApiKey();

        Map<String, Object> body = new HashMap<>();
        body.put("system_instruction", Map.of("parts", List.of(Map.of("text", request.systemPrompt()))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request.userPrompt())))));
        body.put("generationConfig", Map.of(
                "temperature", request.temperature(),
                "maxOutputTokens", request.maxTokens()
        ));

        String model = request.model();
        log.info("[gemini] calling model={} maxTokens={}", model, request.maxTokens());

        Map<String, Object> response;
        try {
            response = webClient.post()
                    .uri(b -> b.path("/v1beta/models/{model}:generateContent").queryParam("key", apiKey).build(model))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(60))
                    .retryWhen(Retry.backoff(1, Duration.ofMillis(2000))
                            .filter(t -> t instanceof WebClientResponseException ex && ex.getStatusCode().is5xxServerError()))
                    .block();
        } catch (WebClientResponseException ex) {
            String hint = ex.getStatusCode().value() == 429
                    ? " (rate limit — Google AI Studio free tier allows 15 req/min; wait a moment and retry)"
                    : "";
            throw new IllegalStateException(
                    "Gemini request failed HTTP " + ex.getStatusCode().value() + hint + ": " + ex.getResponseBodyAsString(), ex);
        }

        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("Gemini returned empty response.");
        }

        List<Map<String, Object>> candidates = listMap(response.get("candidates"));
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Gemini returned no candidates. Response keys: " + response.keySet());
        }

        Map<String, Object> content = map(candidates.get(0).get("content"));
        List<Map<String, Object>> parts = listMap(content.get("parts"));
        String text = parts.isEmpty() ? "" : stringVal(parts.get(0).get("text"), "");

        Map<String, Object> usage = map(response.get("usageMetadata"));
        int inputTokens = intVal(usage.get("promptTokenCount"), 0);
        int outputTokens = intVal(usage.get("candidatesTokenCount"), 0);

        log.info("[gemini] ok inputTokens={} outputTokens={}", inputTokens, outputTokens);
        return new AiGenerationResponse(text, new AiUsage(inputTokens, outputTokens), model);
    }

    @Override
    public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
        throw new UnsupportedOperationException("Gemini embedding not implemented");
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is missing. Set app.ai.gemini.api-key or GEMINI_API_KEY.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listMap(Object v) {
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private int intVal(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private String stringVal(Object v, String fallback) {
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }
}
