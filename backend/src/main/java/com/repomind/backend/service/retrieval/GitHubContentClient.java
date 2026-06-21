package com.repomind.backend.service.retrieval;

import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriUtils;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class GitHubContentClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubContentClient.class);
    private static final int MAX_DOWNLOAD_BYTES = 512_000;

    private final WebClient webClient;

    public GitHubContentClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("https://api.github.com").build();
    }

    public Optional<String> fetchFileContent(Repo repo, User user, String path) {
        if (repo == null || user == null || path == null || path.isBlank()) {
            return Optional.empty();
        }

        String branch = Optional.ofNullable(repo.getDefaultBranch())
                .filter(v -> !v.isBlank())
                .orElse("main");

        String uri = "/repos/%s/%s/contents/%s?ref=%s".formatted(
                repo.getOwner(),
                repo.getName(),
                encodePath(path),
                UriUtils.encode(branch, StandardCharsets.UTF_8)
        );

        try {
            Map<String, Object> response = webClient.get()
                    .uri(uri)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .headers(headers -> applyAuth(headers, user))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(20))
                    .retryWhen(Retry.backoff(2, Duration.ofMillis(250))
                            .filter(this::isRetryable))
                    .block();

            Map<String, Object> payload = map(response);
            String encoding = stringValue(payload.get("encoding"), "");
            String content = stringValue(payload.get("content"), "");
            if ("base64".equalsIgnoreCase(encoding) && !content.isBlank()) {
                return Optional.of(decodeBase64(content));
            }

            String downloadUrl = stringValue(payload.get("download_url"), "");
            if (!downloadUrl.isBlank()) {
                return fetchRaw(downloadUrl, user);
            }
        } catch (Exception ex) {
            log.debug("Failed to fetch GitHub content for {}/{} path={}: {}", repo.getOwner(), repo.getName(), path, ex.getMessage());
        }
        return Optional.empty();
    }

    private Optional<String> fetchRaw(String downloadUrl, User user) {
        try {
            String text = webClient.get()
                    .uri(downloadUrl)
                    .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN_VALUE)
                    .headers(headers -> applyAuth(headers, user))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(20))
                    .retryWhen(Retry.backoff(2, Duration.ofMillis(250))
                            .filter(this::isRetryable))
                    .block();
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            if (text.length() > MAX_DOWNLOAD_BYTES) {
                return Optional.of(text.substring(0, MAX_DOWNLOAD_BYTES));
            }
            return Optional.of(text);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private void applyAuth(HttpHeaders headers, User user) {
        String token = user.getGithubToken();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
    }

    private String decodeBase64(String content) {
        String sanitized = content.replace("\n", "").replace("\r", "");
        byte[] decoded = Base64.getDecoder().decode(sanitized);
        String text = new String(decoded, StandardCharsets.UTF_8);
        if (text.length() > MAX_DOWNLOAD_BYTES) {
            return text.substring(0, MAX_DOWNLOAD_BYTES);
        }
        return text;
    }

    private String encodePath(String path) {
        return List.of(path.split("/")).stream()
                .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "/" + right)
                .orElse(path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Collections.emptyMap();
    }

    private String stringValue(Object value, String fallback) {
        if (value instanceof String s) {
            return s;
        }
        return fallback;
    }

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException ex) {
            return ex.getStatusCode().is5xxServerError()
                    || ex.getStatusCode().value() == 429;
        }
        return true;
    }
}
