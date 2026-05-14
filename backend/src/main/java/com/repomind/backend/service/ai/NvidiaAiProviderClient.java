package com.repomind.backend.service.ai;

import com.repomind.backend.service.ai.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Service
public class NvidiaAiProviderClient implements AiProviderClient {

    private final WebClient webClient;
    private final String apiKey;

    public NvidiaAiProviderClient(
            WebClient.Builder webClientBuilder,
            @Value("${app.ai.nvidia.base-url:https://integrate.api.nvidia.com/v1}") String baseUrl,
            @Value("${app.ai.nvidia.api-key:}") String apiKey
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    @Override
    public boolean supports(String provider) {
        return provider != null && provider.toUpperCase(Locale.ROOT).contains("NVIDIA");
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

        Map<String, Object> response = webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(90))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(400)))
                .block();

        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("NVIDIA chat completion returned empty response.");
        }

        List<Map<String, Object>> choices = listMap(response.get("choices"));
        Map<String, Object> firstChoice = choices.isEmpty() ? Collections.emptyMap() : choices.get(0);
        Map<String, Object> message = map(firstChoice.get("message"));
        String text = stringValue(message.get("content"), "");

        Map<String, Object> usage = map(response.get("usage"));
        int inputTokens = intValue(usage.get("prompt_tokens"), 0);
        int outputTokens = intValue(usage.get("completion_tokens"), 0);
        String model = stringValue(response.get("model"), request.model());

        return new AiGenerationResponse(text, new AiUsage(inputTokens, outputTokens), model);
    }

    @Override
    public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
        requireApiKey();
        Map<String, Object> body = new HashMap<>();
        body.put("model", request.model());
        body.put("input", List.of(request.input()));
        body.put("encoding_format", "float");
        if (request.inputType() != null && !request.inputType().isBlank()) {
            body.put("input_type", request.inputType());
        }

        Map<String, Object> response;
        try {
            response = webClient.post()
                    .uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(60))
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(300))
                            .filter(this::isRetryableNvidiaError))
                    .block();
        } catch (WebClientResponseException ex) {
            String responseBody = ex.getResponseBodyAsString();
            throw new IllegalStateException(
                    "NVIDIA embeddings request failed with HTTP " + ex.getStatusCode().value()
                            + ". Body: " + (responseBody == null ? "" : responseBody),
                    ex
            );
        }

        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("NVIDIA embeddings returned empty response.");
        }

        List<Map<String, Object>> data = listMap(response.get("data"));
        Map<String, Object> firstData = data.isEmpty() ? Collections.emptyMap() : data.get(0);
        List<?> rawVector = list(firstData.get("embedding"));
        List<Double> vector = new ArrayList<>();
        for (Object value : rawVector) {
            if (value instanceof Number n) {
                vector.add(n.doubleValue());
            }
        }
        if (vector.isEmpty()) {
            throw new IllegalStateException("NVIDIA embeddings response returned empty vector.");
        }

        Map<String, Object> usage = map(response.get("usage"));
        int inputTokens = intValue(usage.get("prompt_tokens"), 0);
        int outputTokens = intValue(usage.get("completion_tokens"), 0);
        String model = stringValue(response.get("model"), request.model());
        return new AiEmbeddingResponse(vector, new AiUsage(inputTokens, outputTokens), model);
    }

    private boolean isRetryableNvidiaError(Throwable throwable) {
        if (throwable instanceof TimeoutException) {
            return true;
        }
        if (throwable instanceof WebClientResponseException ex) {
            return ex.getStatusCode().is5xxServerError();
        }
        return true;
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("NVIDIA API key is missing. Configure app.ai.nvidia.api-key.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listMap(Object value) {
        if (value instanceof List<?> l) {
            return (List<Map<String, Object>>) l;
        }
        return List.of();
    }

    private List<?> list(Object value) {
        if (value instanceof List<?> l) {
            return l;
        }
        return List.of();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return fallback;
    }

    private String stringValue(Object value, String fallback) {
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback;
    }
}
