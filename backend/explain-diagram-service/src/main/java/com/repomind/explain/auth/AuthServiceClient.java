package com.repomind.explain.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Users (and their stored GitHub OAuth token) live in auth-service's own
 * database — this replaces the in-process UserRepository read that used to
 * happen here to fetch a user's GitHub token for content lookups.
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
