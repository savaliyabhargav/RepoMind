package com.repomind.backend.service.retrieval;

import com.repomind.backend.domain.repo.FileNode;
import com.repomind.backend.domain.repo.FileNodeRepository;
import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.domain.user.User;
import com.repomind.backend.domain.user.UserRepository;
import com.repomind.backend.service.ai.AiProviderRouter;
import com.repomind.backend.service.ai.dto.AiEmbeddingRequest;
import com.repomind.backend.service.ai.dto.AiEmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class RetrievalService {

    private static final int EMBED_BATCH_SIZE = 24;
    private static final String EMBEDDING_MODEL = "nvidia/nv-embedqa-e5-v5";

    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final FileNodeRepository fileNodeRepository;
    private final AiProviderRouter aiProviderRouter;
    private final QdrantVectorStoreClient qdrantClient;

    private final int maxFilesToEmbed;
    private final int chunkSizeChars;
    private final int chunkOverlapChars;
    private final int maxCharsPerFileForEmbedding;

    public RetrievalService(
            RepoRepository repoRepository,
            UserRepository userRepository,
            FileNodeRepository fileNodeRepository,
            AiProviderRouter aiProviderRouter,
            QdrantVectorStoreClient qdrantClient,
            @Value("${app.vector.embed.max-files:450}") int maxFilesToEmbed,
            @Value("${app.vector.embed.chunk-size-chars:900}") int chunkSizeChars,
            @Value("${app.vector.embed.chunk-overlap-chars:120}") int chunkOverlapChars,
            @Value("${app.vector.embed.max-chars-per-file:2400}") int maxCharsPerFileForEmbedding
    ) {
        this.repoRepository = repoRepository;
        this.userRepository = userRepository;
        this.fileNodeRepository = fileNodeRepository;
        this.aiProviderRouter = aiProviderRouter;
        this.qdrantClient = qdrantClient;
        this.maxFilesToEmbed = maxFilesToEmbed;
        this.chunkSizeChars = chunkSizeChars;
        this.chunkOverlapChars = chunkOverlapChars;
        this.maxCharsPerFileForEmbedding = maxCharsPerFileForEmbedding;
    }

    @Transactional
    public IndexResult indexRepo(UUID repoId, UUID userId, String providerInput) {
        Repo repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + repoId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        String provider = normalizeProvider(providerInput);

        List<FileNode> files = fileNodeRepository.findByRepoIdOrderByPathAsc(repoId).stream()
                .filter(node -> "FILE".equalsIgnoreCase(node.getType()))
                .filter(this::isEmbeddableFile)
                .sorted(Comparator.comparing(this::priorityScore).reversed())
                .limit(maxFilesToEmbed)
                .toList();

        qdrantClient.ensureCollection();

        int embeddedFiles = 0;
        int embeddedChunks = 0;
        int tokensUsed = 0;
        int skippedFiles = 0;

        List<QdrantVectorStoreClient.VectorPoint> upsertBatch = new ArrayList<>();
        for (FileNode file : files) {
            List<String> chunks = buildChunks(buildEmbeddingSourceText(repo, file));
            if (chunks.isEmpty()) {
                skippedFiles++;
                continue;
            }
            int chunkIndex = 0;
            for (String chunk : chunks) {
                AiEmbeddingResponse embedding = aiProviderRouter.resolve(provider)
                        .embed(new AiEmbeddingRequest(provider, EMBEDDING_MODEL, chunk, "passage"));
                tokensUsed += embedding.usage().total();

                String pointId = UUID.randomUUID().toString();
                upsertBatch.add(new QdrantVectorStoreClient.VectorPoint(
                        pointId,
                        embedding.embedding(),
                        Map.of(
                                "repoId", repo.getId().toString(),
                                "repoName", repo.getName(),
                                "fileId", file.getId().toString(),
                                "path", file.getPath(),
                                "chunkIndex", chunkIndex,
                                "text", chunk,
                                "indexedAt", Instant.now().toString()
                        )
                ));
                fileNodeRepository.updateEmbeddingId(file.getId(), pointId);

                chunkIndex++;
                embeddedChunks++;
                if (upsertBatch.size() >= EMBED_BATCH_SIZE) {
                    qdrantClient.upsert(upsertBatch);
                    upsertBatch.clear();
                }
            }
            embeddedFiles++;
        }

        if (!upsertBatch.isEmpty()) {
            qdrantClient.upsert(upsertBatch);
        }

        return new IndexResult(
                repo.getId(),
                user.getId(),
                provider,
                embeddedFiles,
                embeddedChunks,
                skippedFiles,
                tokensUsed,
                chunkSizeChars,
                chunkOverlapChars
        );
    }

    public SearchResult search(UUID repoId, String query, int topK, String providerInput) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query cannot be blank.");
        }
        String provider = normalizeProvider(providerInput);
        AiEmbeddingResponse queryEmbedding = aiProviderRouter.resolve(provider)
                .embed(new AiEmbeddingRequest(provider, EMBEDDING_MODEL, query.trim(), "query"));
        List<QdrantVectorStoreClient.SearchHit> hits = qdrantClient.search(
                queryEmbedding.embedding(),
                repoId.toString(),
                Math.max(1, Math.min(topK, 25))
        );

        List<SearchChunk> chunks = hits.stream()
                .map(hit -> new SearchChunk(
                        hit.fileId(),
                        hit.path(),
                        hit.chunkIndex(),
                        hit.score(),
                        hit.text()
                ))
                .toList();
        return new SearchResult(repoId, provider, query, chunks, queryEmbedding.usage().total());
    }

    private String buildEmbeddingSourceText(Repo repo, FileNode file) {
        String role = Optional.ofNullable(file.getRoleSummary()).orElse(inferRole(file.getPath()));
        String lang = Optional.ofNullable(file.getLanguage()).orElse(inferLanguage(file.getPath()));
        String base = """
                Repository: %s
                Owner: %s
                Path: %s
                Type: %s
                Language: %s
                Role: %s
                """.formatted(
                repo.getName(),
                repo.getOwner(),
                file.getPath(),
                file.getType(),
                lang,
                role
        );
        return base.length() > maxCharsPerFileForEmbedding
                ? base.substring(0, maxCharsPerFileForEmbedding)
                : base;
    }

    private List<String> buildChunks(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int safeChunk = Math.max(200, chunkSizeChars);
        int safeOverlap = Math.max(0, Math.min(chunkOverlapChars, safeChunk / 2));
        int step = safeChunk - safeOverlap;

        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + safeChunk, text.length());
            String chunk = text.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end == text.length()) {
                break;
            }
        }
        return chunks;
    }

    private boolean isEmbeddableFile(FileNode file) {
        long size = Optional.ofNullable(file.getSizeBytes()).orElse(0L);
        if (size > 500_000L) {
            return false;
        }
        String ext = extension(file.getPath());
        return switch (ext) {
            case "java", "kt", "js", "jsx", "ts", "tsx", "py", "go", "rs",
                    "sql", "xml", "json", "yml", "yaml", "md", "properties" -> true;
            default -> false;
        };
    }

    private int priorityScore(FileNode file) {
        String path = file.getPath().toLowerCase(Locale.ROOT);
        int score = 0;
        if (path.contains("controller")) score += 20;
        if (path.contains("service")) score += 20;
        if (path.contains("repository")) score += 20;
        if (path.contains("config")) score += 8;
        if (path.endsWith("readme.md")) score += 30;
        if (path.endsWith("application.yml") || path.endsWith("application.yaml")) score += 16;
        long size = Optional.ofNullable(file.getSizeBytes()).orElse(0L);
        if (size > 0 && size < 100_000) score += 5;
        return score;
    }

    private String normalizeProvider(String providerInput) {
        if (providerInput == null || providerInput.isBlank()) {
            return "NVIDIA_DEV";
        }
        return providerInput.trim().toUpperCase(Locale.ROOT);
    }

    private String extension(String path) {
        int idx = path.lastIndexOf('.');
        if (idx < 0 || idx == path.length() - 1) {
            return "no_ext";
        }
        return path.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String inferRole(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("controller")) return "API entry and request routing";
        if (lower.contains("service")) return "Business rules and orchestration";
        if (lower.contains("repository")) return "Persistence layer access";
        if (lower.contains("config")) return "Runtime configuration";
        return "Application implementation file";
    }

    private String inferLanguage(String path) {
        String ext = extension(path);
        return switch (ext) {
            case "java" -> "Java";
            case "js", "jsx" -> "JavaScript";
            case "ts", "tsx" -> "TypeScript";
            case "py" -> "Python";
            case "go" -> "Go";
            case "rs" -> "Rust";
            case "md" -> "Markdown";
            case "sql" -> "SQL";
            default -> "Unknown";
        };
    }

    public record IndexResult(
            UUID repoId,
            UUID userId,
            String provider,
            int embeddedFiles,
            int embeddedChunks,
            int skippedFiles,
            int tokensUsed,
            int chunkSizeChars,
            int chunkOverlapChars
    ) {
    }

    public record SearchChunk(
            String fileId,
            String path,
            int chunkIndex,
            double score,
            String text
    ) {
    }

    public record SearchResult(
            UUID repoId,
            String provider,
            String query,
            List<SearchChunk> chunks,
            int queryTokensUsed
    ) {
    }
}
