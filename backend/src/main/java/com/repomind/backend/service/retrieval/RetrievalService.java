package com.repomind.backend.service.retrieval;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class RetrievalService {

    public IndexResult indexRepo(UUID repoId, UUID userId, String providerInput) {
        return disabled(repoId, userId, providerInput);
    }

    public IndexResult indexRepo(UUID repoId, UUID userId, String providerInput,
                                  int maxFilesOverride, int maxChunksOverride, long deadlineEpochMs) {
        return disabled(repoId, userId, providerInput);
    }

    public SearchResult search(UUID repoId, String query, int topK, String providerInput) {
        return new SearchResult(repoId, "DISABLED", query, List.of(), 0);
    }

    private IndexResult disabled(UUID repoId, UUID userId, String provider) {
        return new IndexResult(repoId, userId,
                provider != null ? provider : "DISABLED",
                0, 0, 0, 0, 0, 0, 0, 0);
    }

    public record IndexResult(
            UUID repoId, UUID userId, String provider,
            int embeddedFiles, int embeddedChunks, int skippedFiles, int tokensUsed,
            int chunkSizeChars, int chunkOverlapChars, int maxFilesUsed, int maxChunksUsed
    ) {}

    public record SearchChunk(
            String fileId, String path, int chunkIndex, double score, String text
    ) {}

    public record SearchResult(
            UUID repoId, String provider, String query,
            List<SearchChunk> chunks, int queryTokensUsed
    ) {}
}
