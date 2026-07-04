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
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class GroqAiProviderClient implements AiProviderClient {

    private static final Logger log = LoggerFactory.getLogger(GroqAiProviderClient.class);

    private final WebClient webClient;
    private final List<String> apiKeys;
    private final AtomicInteger keyIndex = new AtomicInteger(0);

    // Per-key cooldown: key index → epoch millis until which the key is rate limited.
    // A 429'd key is skipped in the rotation until Groq's Retry-After window passes,
    // so healthy keys share the load instead of every request re-hitting a dead key.
    private final java.util.concurrent.ConcurrentHashMap<Integer, Long> keyCooldownUntil =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final long DEFAULT_COOLDOWN_MS = 60_000L;
    private static final long MAX_COOLDOWN_MS = 15 * 60_000L;

    public GroqAiProviderClient(
            WebClient.Builder webClientBuilder,
            @Value("${app.ai.groq.api-keys:}") List<String> apiKeys
    ) {
        this.webClient = webClientBuilder.baseUrl("https://api.groq.com/openai/v1").build();
        this.apiKeys = apiKeys.stream().filter(k -> k != null && !k.isBlank()).toList();
    }

    @Override
    public boolean supports(String provider) {
        return provider != null && provider.toUpperCase(Locale.ROOT).contains("GROQ");
    }

    @Override
    public AiGenerationResponse generate(AiGenerationRequest request) {
        requireApiKeys();

        Map<String, Object> body = new HashMap<>();
        body.put("model", request.model());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        body.put("stream", false);
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())
        ));

        int size = apiKeys.size();
        long now = System.currentTimeMillis();
        Exception lastException = null;
        int skippedCoolingDown = 0;

        // Round-robin starting position — consecutive requests start on consecutive
        // keys, so load spreads equally across all healthy keys.
        int start = keyIndex.getAndIncrement() & 0x7fffffff;

        for (int i = 0; i < size; i++) {
            int idx = (start + i) % size;

            long coolUntil = keyCooldownUntil.getOrDefault(idx, 0L);
            if (coolUntil > now) {
                skippedCoolingDown++;
                log.debug("[groq] key #{} cooling down for {}s more — skipping", idx + 1, (coolUntil - now) / 1000);
                continue;
            }

            log.info("[groq] key #{} model={} maxTokens={}", idx + 1, request.model(), request.maxTokens());
            try {
                Map<String, Object> response = callGroq(body, apiKeys.get(idx));
                keyCooldownUntil.remove(idx);
                return parseResponse(response, request.model());
            } catch (WebClientResponseException ex) {
                if (ex.getStatusCode().value() == 429) {
                    long cooldownMs = cooldownFromRetryAfter(ex);
                    keyCooldownUntil.put(idx, System.currentTimeMillis() + cooldownMs);
                    log.warn("[groq] key #{} rate limited (429) — cooling down {}s, rotating to next key",
                            idx + 1, cooldownMs / 1000);
                    lastException = ex;
                } else {
                    throw new IllegalStateException(
                            "Groq request failed HTTP " + ex.getStatusCode().value() + ": " + ex.getResponseBodyAsString(), ex);
                }
            }
        }

        throw new IllegalStateException(
                "All " + size + " Groq keys are rate limited ("
                + skippedCoolingDown + " cooling down). Try again later or use another provider.", lastException);
    }

    /** Cooldown from Groq's Retry-After header (seconds), clamped to a sane range. */
    private long cooldownFromRetryAfter(WebClientResponseException ex) {
        try {
            String retryAfter = ex.getHeaders() != null ? ex.getHeaders().getFirst("retry-after") : null;
            if (retryAfter != null) {
                long ms = Long.parseLong(retryAfter.trim()) * 1000L;
                return Math.max(1_000L, Math.min(ms, MAX_COOLDOWN_MS));
            }
        } catch (NumberFormatException ignored) {
            // fall through to default
        }
        return DEFAULT_COOLDOWN_MS;
    }

    @Override
    public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
        throw new UnsupportedOperationException("Groq embedding not implemented");
    }

    private Map<String, Object> callGroq(Map<String, Object> body, String key) {
        Map<String, Object> response;
        try {
            response = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(60))
                    .retryWhen(Retry.backoff(1, Duration.ofMillis(500))
                            .filter(t -> t instanceof WebClientResponseException ex && ex.getStatusCode().is5xxServerError()))
                    .block();
        } catch (WebClientResponseException ex) {
            throw ex;
        }

        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("Groq returned empty response.");
        }
        return response;
    }

    private AiGenerationResponse parseResponse(Map<String, Object> response, String requestModel) {
        List<Map<String, Object>> choices = listMap(response.get("choices"));
        Map<String, Object> message = map(choices.isEmpty() ? null : choices.get(0).get("message"));
        String text = stringVal(message.get("content"), "");
        // strip <think>...</think> blocks emitted by reasoning models
        text = text.replaceAll("(?s)<think>.*?</think>", "").trim();

        Map<String, Object> usage = map(response.get("usage"));
        int inputTokens = intVal(usage.get("prompt_tokens"), 0);
        int outputTokens = intVal(usage.get("completion_tokens"), 0);
        String model = stringVal(response.get("model"), requestModel);

        log.info("[groq] ok inputTokens={} outputTokens={}", inputTokens, outputTokens);
        return new AiGenerationResponse(text, new AiUsage(inputTokens, outputTokens), model);
    }

    private void requireApiKeys() {
        if (apiKeys.isEmpty()) {
            throw new IllegalStateException("No Groq API keys configured. Set app.ai.groq.api-keys or GROQ_API_KEYS.");
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
