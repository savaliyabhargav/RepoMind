package com.repomind.backend.service.retrieval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class QdrantVectorStoreClient {

    private final WebClient webClient;
    private final String collectionName;
    private final int vectorSize;

    public QdrantVectorStoreClient(
            WebClient.Builder webClientBuilder,
            @Value("${app.vector.qdrant.base-url:http://qdrant:6333}") String qdrantBaseUrl,
            @Value("${app.vector.qdrant.collection:repomind-embeddings}") String collectionName,
            @Value("${app.vector.qdrant.vector-size:1024}") int vectorSize
    ) {
        this.webClient = webClientBuilder.baseUrl(qdrantBaseUrl).build();
        this.collectionName = collectionName;
        this.vectorSize = vectorSize;
    }

    public void ensureCollection() {
        Map<String, Object> body = Map.of(
                "vectors", Map.of(
                        "size", vectorSize,
                        "distance", "Cosine"
                )
        );
        try {
            webClient.put()
                    .uri("/collections/{name}", collectionName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .retryWhen(Retry.backoff(2, Duration.ofMillis(250))
                            .filter(this::isRetryable))
                    .block();
        } catch (WebClientResponseException.Conflict ignored) {
            // 409 means collection already exists; treat as healthy.
        }
    }

    public void upsert(List<VectorPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        List<Map<String, Object>> payload = points.stream()
                .map(point -> Map.<String, Object>of(
                        "id", point.id(),
                        "vector", point.vector(),
                        "payload", point.payload()
                ))
                .toList();
        Map<String, Object> body = Map.of("points", payload);

        webClient.put()
                .uri("/collections/{name}/points?wait=true", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(20))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(300))
                        .filter(this::isRetryable))
                .block();
    }

    public List<SearchHit> search(List<Double> vector, String repoId, int limit) {
        Map<String, Object> filter = Map.of(
                "must", List.of(
                        Map.of(
                                "key", "repoId",
                                "match", Map.of("value", repoId)
                        )
                )
        );
        Map<String, Object> body = Map.of(
                "vector", vector,
                "limit", Math.max(1, limit),
                "with_payload", true,
                "with_vector", false,
                "filter", filter
        );

        Map<String, Object> root = webClient.post()
                .uri("/collections/{name}/points/search", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(15))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(250))
                        .filter(this::isRetryable))
                .block();

        if (root == null || root.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> items = listMap(root.get("result"));
        if (items.isEmpty()) return List.of();

        List<SearchHit> hits = new java.util.ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> payloadNode = map(item.get("payload"));
            hits.add(new SearchHit(
                    stringValue(item.get("id"), ""),
                    doubleValue(item.get("score"), 0.0),
                    stringValue(payloadNode.get("repoId"), ""),
                    stringValue(payloadNode.get("fileId"), ""),
                    stringValue(payloadNode.get("path"), ""),
                    intValue(payloadNode.get("chunkIndex"), 0),
                    stringValue(payloadNode.get("text"), "")
            ));
        }
        return hits;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listMap(Object value) {
        if (value instanceof List<?> l) {
            return (List<Map<String, Object>>) l;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Collections.emptyMap();
    }

    private String stringValue(Object value, String fallback) {
        if (value instanceof String s) return s;
        return fallback;
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        return fallback;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        return fallback;
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException ex) {
            return ex.getStatusCode().is5xxServerError();
        }
        return true;
    }

    public record VectorPoint(String id, List<Double> vector, Map<String, Object> payload) {
    }

    public record SearchHit(
            String id,
            double score,
            String repoId,
            String fileId,
            String path,
            int chunkIndex,
            String text
    ) {
    }
}
