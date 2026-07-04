package com.repomind.backend.service.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;

@Service
public class GitHubService {

    private final WebClient webClient;

    @Value("${spring.security.oauth2.client.registration.github.client-id:}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.github.client-secret:}")
    private String clientSecret;

    public GitHubService(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<String> getAccessToken(String code) {
        return webClient.post()
                .uri("https://github.com/login/oauth/access_token")
                .header("Accept", "application/json")
                .bodyValue(Map.of(
                        "client_id", clientId,
                        "client_secret", clientSecret,
                        "code", code
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    if (response.containsKey("error")) {
                        throw new RuntimeException(
                            "GitHub token exchange failed: " + response.get("error_description"));
                    }
                    String token = (String) response.get("access_token");
                    if (token == null) {
                        throw new RuntimeException("GitHub returned no access_token");
                    }
                    return token;
                });
    }

    public Mono<Map<String, Object>> getGitHubProfile(String accessToken) {
        return webClient.get()
                .uri("https://api.github.com/user")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(Map.class)
                .map(profile -> (Map<String, Object>) profile);
    }
}
