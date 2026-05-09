package com.repomind.backend.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.repomind.backend.service.ai.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

        JsonNode node = webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(90))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(400)))
                .block();

        if (node == null) {
            throw new IllegalStateException("NVIDIA chat completion returned empty response.");
        }

        String text = node.path("choices").path(0).path("message").path("content").asText("");
        int inputTokens = node.path("usage").path("prompt_tokens").asInt(0);
        int outputTokens = node.path("usage").path("completion_tokens").asInt(0);
        String model = node.path("model").asText(request.model());

        return new AiGenerationResponse(text, new AiUsage(inputTokens, outputTokens), model);
    }

    @Override
    public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
        requireApiKey();
        Map<String, Object> body = Map.of(
                "model", request.model(),
                "input", request.input()
        );

        JsonNode node = webClient.post()
                .uri("/embeddings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(60))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(300)))
                .block();

        if (node == null) {
            throw new IllegalStateException("NVIDIA embeddings returned empty response.");
        }

        JsonNode vectorNode = node.path("data").path(0).path("embedding");
        if (!vectorNode.isArray()) {
            throw new IllegalStateException("NVIDIA embeddings response missing vector array.");
        }

        List<Double> vector = new ArrayList<>();
        vectorNode.forEach(value -> vector.add(value.asDouble()));
        if (vector.isEmpty()) {
            throw new IllegalStateException("NVIDIA embeddings response returned empty vector.");
        }

        int inputTokens = node.path("usage").path("prompt_tokens").asInt(0);
        int outputTokens = node.path("usage").path("completion_tokens").asInt(0);
        String model = node.path("model").asText(request.model());
        return new AiEmbeddingResponse(vector, new AiUsage(inputTokens, outputTokens), model);
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("NVIDIA API key is missing. Configure app.ai.nvidia.api-key.");
        }
    }
}
