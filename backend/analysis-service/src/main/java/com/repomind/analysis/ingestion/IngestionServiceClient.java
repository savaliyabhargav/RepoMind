package com.repomind.analysis.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Repos/canonical-repos/file-nodes now live in repo-ingestion-service's own
 * database — this replaces the in-process RepoRepository/FileNodeRepository
 * reads (and the one write, role-summary annotation) that used to happen here.
 */
@Service
public class IngestionServiceClient {

    private final WebClient webClient;

    public IngestionServiceClient(
            WebClient.Builder webClientBuilder,
            @Value("${app.ingestion-service.base-url:http://localhost:8082}") String baseUrl
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    public record RepoSummary(
            UUID id,
            UUID userId,
            UUID canonicalRepoId,
            String owner,
            String name,
            String defaultBranch,
            String status
    ) {}

    public record FileNodeSummary(
            UUID id,
            String path,
            String name,
            String type,
            Integer depth,
            Long sizeBytes,
            String language,
            String roleSummary,
            String embeddingId,
            boolean isInScope
    ) {}

    public Optional<RepoSummary> findRepo(UUID repoId) {
        try {
            RepoSummary summary = webClient.get()
                    .uri("/internal/repos/{repoId}", repoId)
                    .retrieve()
                    .bodyToMono(RepoSummary.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return Optional.ofNullable(summary);
        } catch (WebClientResponseException.NotFound ex) {
            return Optional.empty();
        }
    }

    /** All file nodes for a canonical repo, ordered by path (any type). */
    public List<FileNodeSummary> listFiles(UUID canonicalRepoId) {
        return listFiles(canonicalRepoId, null);
    }

    /** File nodes for a canonical repo, ordered by path, optionally filtered by type ("FILE"/"DIRECTORY"). */
    public List<FileNodeSummary> listFiles(UUID canonicalRepoId, String type) {
        FileNodeSummary[] result = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/canonical-repos/{canonicalRepoId}/files")
                        .queryParamIfPresent("type", Optional.ofNullable(type))
                        .build(canonicalRepoId))
                .retrieve()
                .bodyToMono(FileNodeSummary[].class)
                .timeout(Duration.ofSeconds(20))
                .block();
        return result == null ? List.of() : Arrays.asList(result);
    }

    public Optional<FileNodeSummary> findFile(UUID fileId) {
        try {
            FileNodeSummary summary = webClient.get()
                    .uri("/internal/files/{fileId}", fileId)
                    .retrieve()
                    .bodyToMono(FileNodeSummary.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return Optional.ofNullable(summary);
        } catch (WebClientResponseException.NotFound ex) {
            return Optional.empty();
        }
    }

    public void updateRoleSummary(UUID fileId, String roleSummary) {
        webClient.patch()
                .uri("/internal/files/{fileId}/role-summary", fileId)
                .bodyValue(Map.of("roleSummary", roleSummary == null ? "" : roleSummary))
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(10))
                .block();
    }
}
