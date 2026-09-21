package com.repomind.aiprovider;

import com.repomind.aiprovider.dto.*;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Talks to any OpenAI-compatible server (Ollama, llama.cpp, vLLM, LM Studio, a Kaggle/Colab
 * notebook behind an ngrok/cloudflared tunnel, a paid GPU box, ...).
 *
 * <p>Several endpoints can be configured, in priority order. A call goes to the first healthy
 * endpoint; if it fails (connection refused, tunnel gone, timeout, 4xx/5xx) the endpoint is put
 * on a short cool-down and the next one is tried. If every endpoint is cooling down they are all
 * tried anyway, so a recovered endpoint is never locked out.
 */
@Slf4j
@Service
public class LocalAiProviderClient implements AiProviderClient {

    static final Set<String> PROVIDER_NAMES =
            Set.of("LOCAL", "OLLAMA", "KAGGLE", "OPENAI_COMPATIBLE", "CUSTOM");

    private final List<Endpoint> endpoints;
    private final String apiKey;
    private final String model;
    private final String embeddingModel;
    private final String reasoningEffort;
    private final Duration timeout;
    private final Duration cooldown;
    private final LongSupplier clock;

    @Autowired
    public LocalAiProviderClient(
            @Value("${app.ai.local.endpoints:http://localhost:11434/v1}") String endpoints,
            @Value("${app.ai.local.api-key:}") String apiKey,
            @Value("${app.ai.local.model:}") String model,
            @Value("${app.ai.local.embedding-model:}") String embeddingModel,
            @Value("${app.ai.local.timeout-seconds:300}") long timeoutSeconds,
            @Value("${app.ai.local.cooldown-seconds:30}") long cooldownSeconds,
            @Value("${app.ai.local.reasoning-effort:none}") String reasoningEffort
    ) {
        this(endpoints, apiKey, model, embeddingModel, timeoutSeconds, cooldownSeconds, reasoningEffort,
                System::currentTimeMillis);
    }

    LocalAiProviderClient(
            String endpoints,
            String apiKey,
            String model,
            String embeddingModel,
            long timeoutSeconds,
            long cooldownSeconds,
            String reasoningEffort,
            LongSupplier clock
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.embeddingModel = embeddingModel;
        this.reasoningEffort = reasoningEffort;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.cooldown = Duration.ofSeconds(Math.max(0, cooldownSeconds));
        this.clock = clock;
        this.endpoints = buildEndpoints(endpoints, this.timeout);
        log.info("Local AI provider ready with {} endpoint(s): {}", this.endpoints.size(),
                this.endpoints.stream().map(e -> e.baseUrl).toList());
    }

    @Override
    public boolean supports(String provider) {
        return provider != null && PROVIDER_NAMES.contains(provider.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public AiGenerationResponse generate(AiGenerationRequest request) {
        String effectiveModel = pick(model, request.model());
        Map<String, Object> body = new HashMap<>();
        body.put("model", effectiveModel);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        body.put("stream", false);
        // "Thinking" models (Gemma 4, Qwen3, ...) otherwise burn the token budget on hidden reasoning and can
        // return empty content. Blank = do not send the field (for servers that reject unknown parameters).
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            body.put("reasoning_effort", reasoningEffort.trim());
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", request.userPrompt())
        ));

        Map<String, Object> response = post("chat completion", "/chat/completions", body);

        List<Map<String, Object>> choices = listMap(response.get("choices"));
        Map<String, Object> firstChoice = choices.isEmpty() ? Collections.emptyMap() : choices.get(0);
        Map<String, Object> message = map(firstChoice.get("message"));
        String text = stringValue(message.get("content"), "");

        Map<String, Object> usage = map(response.get("usage"));
        return new AiGenerationResponse(
                text,
                new AiUsage(intValue(usage.get("prompt_tokens")), intValue(usage.get("completion_tokens"))),
                stringValue(response.get("model"), effectiveModel)
        );
    }

    @Override
    public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
        String effectiveModel = pick(embeddingModel, request.model());
        Map<String, Object> body = new HashMap<>();
        body.put("model", effectiveModel);
        body.put("input", List.of(request.input()));

        Map<String, Object> response = post("embeddings", "/embeddings", body);

        List<Map<String, Object>> data = listMap(response.get("data"));
        Map<String, Object> first = data.isEmpty() ? Collections.emptyMap() : data.get(0);
        List<Double> vector = new ArrayList<>();
        if (first.get("embedding") instanceof List<?> raw) {
            for (Object value : raw) {
                if (value instanceof Number n) {
                    vector.add(n.doubleValue());
                }
            }
        }
        if (vector.isEmpty()) {
            throw new IllegalStateException("Local embeddings response returned an empty vector.");
        }

        Map<String, Object> usage = map(response.get("usage"));
        return new AiEmbeddingResponse(
                vector,
                new AiUsage(intValue(usage.get("prompt_tokens")), intValue(usage.get("completion_tokens"))),
                stringValue(response.get("model"), effectiveModel)
        );
    }

    // ── failover ──────────────────────────────────────────────────────────────

    private Map<String, Object> post(String what, String path, Map<String, Object> body) {
        if (endpoints.isEmpty()) {
            throw new IllegalStateException("No local LLM endpoints configured. Set app.ai.local.endpoints.");
        }

        long now = clock.getAsLong();
        List<Endpoint> healthy = endpoints.stream().filter(e -> e.unhealthyUntil <= now).toList();
        List<Endpoint> order = healthy.isEmpty() ? endpoints : healthy;

        Exception last = null;
        for (Endpoint endpoint : order) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> response = endpoint.client.post()
                        .uri(path)
                        .headers(headers -> {
                            if (apiKey != null && !apiKey.isBlank()) {
                                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
                            }
                            // ngrok's free tier serves an HTML interstitial to unknown clients
                            headers.set("ngrok-skip-browser-warning", "true");
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .timeout(timeout)
                        .block();
                if (response == null || response.isEmpty()) {
                    throw new IllegalStateException("empty response");
                }
                endpoint.unhealthyUntil = 0;
                return response;
            } catch (Exception ex) {
                endpoint.unhealthyUntil = clock.getAsLong() + cooldown.toMillis();
                last = ex;
                log.warn("Local LLM endpoint {} failed for {}: {}", endpoint.baseUrl, what, describe(ex));
            }
        }
        throw new IllegalStateException(
                "All local LLM endpoints failed for " + what + " (" + order.size() + " tried). Last error: " + describe(last),
                last
        );
    }

    private static String describe(Throwable ex) {
        if (ex instanceof WebClientResponseException http) {
            String responseBody = http.getResponseBodyAsString();
            String trimmed = responseBody == null ? "" : responseBody.strip();
            if (trimmed.length() > 300) {
                trimmed = trimmed.substring(0, 300) + "...";
            }
            return "HTTP " + http.getStatusCode().value() + (trimmed.isEmpty() ? "" : " " + trimmed);
        }
        return ex == null ? "unknown" : ex.toString();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<Endpoint> buildEndpoints(String csv, Duration responseTimeout) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(responseTimeout);
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(url -> !url.isEmpty())
                .map(url -> url.endsWith("/") ? url.substring(0, url.length() - 1) : url)
                .map(url -> new Endpoint(url, WebClient.builder()
                        .baseUrl(url)
                        .clientConnector(new ReactorClientHttpConnector(httpClient))
                        .build()))
                .toList();
    }

    /** The configured model wins; the caller's model name is only a fallback. */
    private static String pick(String configured, String requested) {
        return configured != null && !configured.isBlank() ? configured : requested;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listMap(Object value) {
        return value instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private static int intValue(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String stringValue(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static final class Endpoint {
        final String baseUrl;
        final WebClient client;
        volatile long unhealthyUntil;

        Endpoint(String baseUrl, WebClient client) {
            this.baseUrl = baseUrl;
            this.client = client;
        }
    }
}
