package com.repomind.ingestion.api;

import com.repomind.ingestion.domain.FileNode;
import com.repomind.ingestion.domain.FileNodeRepository;
import com.repomind.ingestion.domain.Repo;
import com.repomind.ingestion.domain.RepoRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service endpoints only — not gateway-routed to the frontend.
 * explain-diagram-service and analysis-service call these instead of reading
 * Repo/FileNode in-process now that this data lives in its own database.
 */
@RestController
@RequestMapping("/internal")
public class RepoInternalController {

    private final RepoRepository repoRepository;
    private final FileNodeRepository fileNodeRepository;

    public RepoInternalController(RepoRepository repoRepository, FileNodeRepository fileNodeRepository) {
        this.repoRepository = repoRepository;
        this.fileNodeRepository = fileNodeRepository;
    }

    @GetMapping("/repos/{repoId}")
    public ResponseEntity<RepoSummary> getRepo(@PathVariable UUID repoId) {
        return repoRepository.findById(repoId)
                .map(RepoSummary::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/canonical-repos/{canonicalRepoId}/files")
    public List<FileNodeSummary> listFiles(
            @PathVariable UUID canonicalRepoId,
            @RequestParam(required = false) String type) {
        // Always path-ordered, regardless of type filter, so every caller sees the
        // same ordering the old findByCanonicalRepoIdOrderByPathAsc() query gave them.
        return fileNodeRepository.findByCanonicalRepoIdOrderByPathAsc(canonicalRepoId).stream()
                .filter(n -> type == null || type.equalsIgnoreCase(n.getType()))
                .map(FileNodeSummary::from)
                .toList();
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<FileNodeSummary> getFile(@PathVariable UUID fileId) {
        return fileNodeRepository.findById(fileId)
                .map(FileNodeSummary::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PatchMapping("/files/{fileId}/role-summary")
    public void updateRoleSummary(@PathVariable UUID fileId, @RequestBody Map<String, String> body) {
        fileNodeRepository.updateRoleSummary(fileId, body.get("roleSummary"));
    }

    public record RepoSummary(
            UUID id,
            UUID userId,
            UUID canonicalRepoId,
            String owner,
            String name,
            String defaultBranch,
            String status
    ) {
        static RepoSummary from(Repo repo) {
            return new RepoSummary(
                    repo.getId(),
                    repo.getUserId(),
                    repo.getCanonicalRepo() != null ? repo.getCanonicalRepo().getId() : null,
                    repo.getOwner(),
                    repo.getName(),
                    repo.getDefaultBranch(),
                    repo.getStatus()
            );
        }
    }

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
    ) {
        static FileNodeSummary from(FileNode fileNode) {
            return new FileNodeSummary(
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
