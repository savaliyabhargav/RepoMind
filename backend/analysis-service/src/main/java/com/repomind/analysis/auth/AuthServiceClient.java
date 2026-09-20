package com.repomind.analysis.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Users now live in auth-service's own database — this replaces the direct
 * in-process UserRepository reads that used to happen here.
 */
@Service
public class AuthServiceClient {

    private final WebClient webClient;

    public AuthServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${app.auth-service.base-url:http://localhost:8081}") String baseUrl
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    public record UserSummary(UUID id, String username, String avatarUrl, String plan) {}

    public Optional<UserSummary> findUser(UUID userId) {
        try {
            UserSummary summary = webClient.get()
                    .uri("/internal/users/{userId}", userId)
                    .retrieve()
                    .bodyToMono(UserSummary.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return Optional.ofNullable(summary);
        } catch (WebClientResponseException.NotFound ex) {
            return Optional.empty();
        }
    }

    public boolean userExists(UUID userId) {
        return findUser(userId).isPresent();
    }

    public String getGithubToken(UUID userId) {
        try {
            Map<?, ?> response = webClient.get()
                    .uri("/internal/users/{userId}/github-token", userId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            Object token = response == null ? null : response.get("githubToken");
            return token instanceof String s ? s : "";
        } catch (WebClientResponseException.NotFound ex) {
            return "";
        }
    }
}
