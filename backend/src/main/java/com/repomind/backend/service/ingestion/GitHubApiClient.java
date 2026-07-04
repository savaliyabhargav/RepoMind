package com.repomind.backend.service.ingestion;

import com.repomind.backend.service.ingestion.dto.GitHubRepoResponse;
import com.repomind.backend.service.ingestion.dto.GitHubTreeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Service
@Slf4j
public class GitHubApiClient {

    private final WebClient webClient;
    private final GitHubRateLimitTracker rateLimitTracker;

    @Value("${app.github.token:}")
    private String githubToken;

    public GitHubApiClient(WebClient.Builder webClientBuilder, GitHubRateLimitTracker rateLimitTracker) {
        this.rateLimitTracker = rateLimitTracker;
        this.webClient = webClientBuilder.baseUrl("https://api.github.com").build();
    }

    public Mono<GitHubRepoResponse> getRepoInfo(String owner, String repo) {
        return get("/repos/{owner}/{repo}", new Object[]{owner, repo}, GitHubRepoResponse.class);
    }

    public Mono<GitHubTreeResponse> getTree(String owner, String repo, String branch) {
        return get("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1",
                new Object[]{owner, repo, branch}, GitHubTreeResponse.class);
    }

    private <T> Mono<T> get(String uri, Object[] uriVars, Class<T> responseType) {
        return webClient.get()
                .uri(uri, uriVars)
                .headers(h -> {
                    if (githubToken != null && !githubToken.isBlank()) {
                        h.setBearerAuth(githubToken);
                    }
                })
                .exchangeToMono(response -> {
                    HttpHeaders headers = response.headers().asHttpHeaders();
                    updateRateLimitHeaders(headers);

                    int status = response.statusCode().value();

                    // Rate limit: GitHub sends 403 (not 429) when the hourly budget is gone
                    if ((status == 403 || status == 429) && isRateLimitExhausted(headers)) {
                        long resetAt = parseResetAt(headers);
                        log.warn("GitHub rate limit hit (status={}) — resets at epoch {}", status, resetAt);
                        return response.releaseBody()
                                .then(Mono.error(new GitHubRateLimitException(resetAt)));
                    }

                    // 5xx — GitHub server-side problem, worth retrying
                    if (status >= 500) {
                        log.warn("GitHub returned {} for {} — transient server error, eligible for retry", status, uri);
                        return response.releaseBody()
                                .then(Mono.error(new GitHubTransientException("GitHub " + status + " error")));
                    }

                    // 4xx (excluding rate-limit 403 above) — permanent failures:
                    // repo not found, bad credentials, etc. Retrying will not help.
                    if (response.statusCode().isError()) {
                        return response.createException().flatMap(Mono::error);
                    }

                    return response.bodyToMono(responseType);
                })
                // Network-level failures (SSL errors, connection reset, DNS, timeout) are transient.
                // Exclude WebClientResponseException (those are real HTTP error responses handled above)
                // and exceptions already classified as rate-limit or transient.
                .onErrorMap(
                        ex -> !(ex instanceof WebClientResponseException)
                              && !(ex instanceof GitHubRateLimitException)
                              && !(ex instanceof GitHubTransientException),
                        ex -> new GitHubTransientException("GitHub network error: " + ex.getMessage(), ex)
                );
    }

    private void updateRateLimitHeaders(HttpHeaders headers) {
        String remaining = headers.getFirst("X-RateLimit-Remaining");
        String reset = headers.getFirst("X-RateLimit-Reset");
        if (remaining != null && reset != null) {
            try {
                rateLimitTracker.update(Long.parseLong(remaining), Long.parseLong(reset));
            } catch (NumberFormatException ex) {
                log.warn("Could not parse GitHub rate limit headers: remaining={} reset={}", remaining, reset);
            }
        }
    }

    private boolean isRateLimitExhausted(HttpHeaders headers) {
        String remaining = headers.getFirst("X-RateLimit-Remaining");
        return remaining != null && Long.parseLong(remaining) == 0;
    }

    private long parseResetAt(HttpHeaders headers) {
        String reset = headers.getFirst("X-RateLimit-Reset");
        if (reset != null) {
            try {
                return Long.parseLong(reset);
            } catch (NumberFormatException ignored) {}
        }
        return Instant.now().plusSeconds(3600).getEpochSecond();
    }
}
