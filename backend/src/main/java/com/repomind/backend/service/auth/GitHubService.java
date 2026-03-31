package com.repomind.backend.service.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;

@Service
public class GitHubService {

    private final WebClient webClient;

    @Value("${GITHUB_CLIENT_ID}")
    private String clientId;

    @Value("${GITHUB_CLIENT_SECRET}")
    private String clientSecret;

    public GitHubService(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Swaps the OAuth2 'code' for a GitHub Access Token.
     */
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
                .map(response -> (String) response.get("access_token"));
    }

    /**
     * Uses the Access Token to fetch the user's GitHub profile data.
     */
    public Mono<Map<String, Object>> getGitHubProfile(String accessToken) {
        return webClient.get()
                .uri("https://api.github.com/user")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(Map.class)
                .map(profile -> (Map<String, Object>) profile);
    }
}
