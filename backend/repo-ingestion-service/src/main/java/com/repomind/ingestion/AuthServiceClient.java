package com.repomind.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Users live in auth-service's own database — this replaces the in-process
 * UserRepository read that used to happen here.
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
}
