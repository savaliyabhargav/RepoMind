package com.repomind.backend.service.ingestion;

import com.repomind.backend.service.ingestion.dto.GitHubRepoResponse;
import com.repomind.backend.service.ingestion.dto.GitHubTreeResponse; // Added import
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class GitHubApiClient {

    private final WebClient webClient;

    @Value("${app.github.token:}")
    private String githubToken;

    public GitHubApiClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl("https://api.github.com").build();
    }

    public Mono<GitHubRepoResponse> getRepoInfo(String owner, String repo) {
        return this.webClient.get()
                .uri("/repos/{owner}/{repo}", owner, repo)
                .headers(h -> {
                    if (githubToken != null && !githubToken.isBlank()) {
                        h.setBearerAuth(githubToken);
                    }
                })
                .retrieve()
                .bodyToMono(GitHubRepoResponse.class);
    }

    // New Method: Fetches the entire file tree recursively
    public Mono<GitHubTreeResponse> getTree(String owner, String repo, String branch) {
        return this.webClient.get()
                .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                .headers(h -> {
                    if (githubToken != null && !githubToken.isBlank()) {
                        h.setBearerAuth(githubToken);
                    }
                })
                .retrieve()
                .bodyToMono(GitHubTreeResponse.class);
    }
}