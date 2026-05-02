package com.repomind.backend.api.repo;

import com.repomind.backend.domain.repo.FileNode;
import com.repomind.backend.domain.repo.FileNodeRepository;
import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.service.ingestion.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/repo")
@RequiredArgsConstructor
public class RepoController {

    private final IngestionService ingestionService;
    private final RepoRepository repoRepository;
    private final FileNodeRepository fileNodeRepository;

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody Map<String, String> request) {
        try {
            String url = request.get("url");
            String userIdStr = request.get("userId");

            if (url == null || userIdStr == null) {
                return ResponseEntity.badRequest().body("Missing 'url' or 'userId' in request body");
            }

            UUID userId = UUID.fromString(userIdStr);
            Repo result = ingestionService.ingestRepository(url, userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Ingestion failed: " + e.getMessage());
        }
    }

    @GetMapping("/{repoId}")
    public ResponseEntity<?> getRepo(@PathVariable UUID repoId) {
        return repoRepository.findById(repoId)
                .map(repo -> ResponseEntity.ok(RepoResponse.from(repo)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{repoId}/tree")
    public ResponseEntity<?> getRepoTree(@PathVariable UUID repoId) {
        if (!repoRepository.existsById(repoId)) {
            return ResponseEntity.notFound().build();
        }

        List<FileNodeResponse> nodes = fileNodeRepository.findByRepoIdOrderByPathAsc(repoId)
                .stream()
                .map(FileNodeResponse::from)
                .toList();

        return ResponseEntity.ok(nodes);
    }

    public record RepoResponse(
            UUID id,
            UserSummary user,
            String url,
            String name,
            String owner,
            String provider,
            String defaultBranch,
            Integer fileCount,
            Long sizeKb,
            String status,
            boolean isPrivate,
            String errorMsg,
            Instant createdAt
    ) {
        static RepoResponse from(Repo repo) {
            return new RepoResponse(
                    repo.getId(),
                    UserSummary.from(repo),
                    repo.getUrl(),
                    repo.getName(),
                    repo.getOwner(),
                    repo.getProvider(),
                    repo.getDefaultBranch(),
                    repo.getFileCount(),
                    repo.getSizeKb(),
                    repo.getStatus(),
                    repo.isPrivate(),
                    repo.getErrorMsg(),
                    repo.getCreatedAt()
            );
        }
    }

    public record UserSummary(UUID id, String username, String avatarUrl, String plan) {
        static UserSummary from(Repo repo) {
            return new UserSummary(
                    repo.getUser().getId(),
                    repo.getUser().getUsername(),
                    repo.getUser().getAvatarUrl(),
                    repo.getUser().getPlan().name()
            );
        }
    }

    public record FileNodeResponse(
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
    ) {
        static FileNodeResponse from(FileNode fileNode) {
            return new FileNodeResponse(
                    fileNode.getId(),
                    fileNode.getPath(),
                    fileNode.getName(),
                    fileNode.getType(),
                    fileNode.getDepth(),
                    fileNode.getSizeBytes(),
                    fileNode.getLanguage(),
                    fileNode.getRoleSummary(),
                    fileNode.getEmbeddingId(),
                    fileNode.isInScope()
            );
        }
    }
}
