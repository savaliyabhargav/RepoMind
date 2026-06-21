package com.repomind.backend.service.ai;

import com.repomind.backend.service.ai.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;

@Service
public class GroqAiProviderClient implements AiProviderClient {

    private static final Logger log = LoggerFactory.getLogger(GroqAiProviderClient.class);

    private final WebClient webClient;
    private final String apiKey;

    public GroqAiProviderClient(
            WebClient.Builder webClientBuilder,
            @Value("${app.ai.groq.api-key:}") String apiKey
    ) {
        this.webClient = webClientBuilder.baseUrl("https://api.groq.com/openai/v1").build();
        this.apiKey = apiKey;
    }

    @Override
    public boolean supports(String provider) {
        return provider != null && provider.toUpperCase(Locale.ROOT).contains("GROQ");
    }

    @Override
    public AiGenerationResponse generate(AiGenerationRequest request) {
        requireApiKey();

        Map<String, Object> body = new HashMap<>();
        body.put("model", request.model());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())
        ));

        log.info("[groq] calling model={} maxTokens={}", request.model(), request.maxTokens());

        Map<String, Object> response;
        try {
            response = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(60))
                    .retryWhen(Retry.backoff(1, Duration.ofMillis(500))
                            .filter(t -> t instanceof WebClientResponseException ex && ex.getStatusCode().is5xxServerError()))
                    .block();
        } catch (WebClientResponseException ex) {
            String hint = ex.getStatusCode().value() == 429 ? " (rate limit — wait a moment and retry)" : "";
            throw new IllegalStateException(
                    "Groq request failed HTTP " + ex.getStatusCode().value() + hint + ": " + ex.getResponseBodyAsString(), ex);
        }

        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("Groq returned empty response.");
        }

        List<Map<String, Object>> choices = listMap(response.get("choices"));
        Map<String, Object> message = map(choices.isEmpty() ? null : choices.get(0).get("message"));
        String text = stringVal(message.get("content"), "");
        // strip <think>...</think> blocks emitted by reasoning models (e.g. QwQ, DeepSeek-R1)
        text = text.replaceAll("(?s)<think>.*?</think>", "").trim();

        Map<String, Object> usage = map(response.get("usage"));
        int inputTokens = intVal(usage.get("prompt_tokens"), 0);
        int outputTokens = intVal(usage.get("completion_tokens"), 0);
        String model = stringVal(response.get("model"), request.model());

        log.info("[groq] ok inputTokens={} outputTokens={}", inputTokens, outputTokens);
        return new AiGenerationResponse(text, new AiUsage(inputTokens, outputTokens), model);
    }

    @Override
    public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
        throw new UnsupportedOperationException("Groq embedding not implemented");
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Groq API key is missing. Set app.ai.groq.api-key or GROQ_API_KEY.");
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
