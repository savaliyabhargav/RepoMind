package com.repomind.backend.api.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.backend.domain.repo.FileNode;
import com.repomind.backend.domain.repo.FileNodeRepository;
import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.domain.user.User;
import com.repomind.backend.domain.user.UserRepository;
import com.repomind.backend.service.ingestion.GitHubUrlParser;
import com.repomind.backend.service.ingestion.kafka.FileNodeMessage;
import com.repomind.backend.service.ingestion.kafka.RepoIngestionProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/repo")
@RequiredArgsConstructor
@Slf4j
public class RepoController {

    private final RepoIngestionProducer repoIngestionProducer;
    private final RepoRepository repoRepository;
    private final FileNodeRepository fileNodeRepository;
    private final UserRepository userRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody Map<String, String> request) {
        String url = request.get("url");
        String userIdStr = request.get("userId");

        if (url == null || userIdStr == null) {
            return ResponseEntity.badRequest()
                    .body("Missing 'url' or 'userId' in request body");
        }

        if (!GitHubUrlParser.isValid(url)) {
            return ResponseEntity.badRequest().body("Invalid GitHub URL");
        }

        UUID userId = UUID.fromString(userIdStr);
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body("User not found");
        }

        // Return existing repo if this URL was already ingested by this user
        var existing = repoRepository.findByUrlAndUserId(url, userId);
        if (existing.isPresent()) {
            Repo repo = existing.get();
            log.info("Duplicate ingest request for url={} userId={} — returning existing repoId={}", url, userId, repo.getId());
            return ResponseEntity.ok(Map.of(
                    "repoId", repo.getId(),
                    "status", repo.getStatus(),
                    "message", "Repository already ingested"
            ));
        }

        String owner = GitHubUrlParser.extractOwner(url);
        String name = GitHubUrlParser.extractRepoName(url);

        Repo repo = Repo.builder()
                .user(user)
                .url(url)
                .name(name)
                .owner(owner)
                .provider("GITHUB")
                .status("PENDING")
                .build();
        try {
            repo = repoRepository.save(repo);
        } catch (DataIntegrityViolationException ex) {
            // Race condition: two concurrent requests slipped past the findByUrlAndUserId check
            // The unique constraint on (user_id, url) caught it — return the existing row
            Repo raceWinner = repoRepository.findByUrlAndUserId(url, userId)
                    .orElseThrow(() -> new IllegalStateException("Constraint violated but repo not found"));
            return ResponseEntity.ok(Map.of(
                    "repoId", raceWinner.getId(),
                    "status", raceWinner.getStatus(),
                    "message", "Repository already ingested"
            ));
        }

        repoIngestionProducer.submitIngestion(repo.getId(), url, userId);

        return ResponseEntity.accepted().body(Map.of(
                "repoId", repo.getId(),
                "status", "PENDING",
                "message", "Repository ingestion started"
        ));
    }

    @GetMapping
    public ResponseEntity<?> listRepos(@RequestParam UUID userId) {
        if (!userRepository.existsById(userId)) {
            return ResponseEntity.badRequest().body("User not found");
        }
        List<RepoResponse> repos = repoRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(RepoResponse::from)
                .toList();
        return ResponseEntity.ok(repos);
    }

    @GetMapping("/{repoId}")
    public ResponseEntity<?> getRepo(@PathVariable UUID repoId) {
        return repoRepository.findById(repoId)
                .map(repo -> ResponseEntity.ok(RepoResponse.from(repo)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{repoId}/tree")
    public ResponseEntity<?> getRepoTree(@PathVariable UUID repoId) {
        Repo repo = repoRepository.findById(repoId).orElse(null);
        if (repo == null) {
            return ResponseEntity.notFound().build();
        }

        // Check Redis first (~1ms) — worker caches tree here immediately after GitHub fetch
        String redisKey = "repo:tree:" + repoId;
        String cached = redisTemplate.opsForValue().get(redisKey);

        if (cached != null) {
            try {
                List<FileNodeMessage> nodes = objectMapper.readValue(
                        cached, new TypeReference<>() {});
                log.info("Tree served from Redis for repoId={} nodes={}", repoId, nodes.size());
                return ResponseEntity.ok(Map.of(
                        "source", "cache",
                        "nodes", nodes
                ));
            } catch (Exception ex) {
                log.warn("Failed to parse Redis cache for repoId={}, falling through to DB", repoId);
            }
        }

        // No Redis — check if ingestion has linked a canonical yet
        var canonical = repo.getCanonicalRepo();
        if (canonical == null) {
            return ResponseEntity.accepted().body(Map.of(
                    "source", "pending",
                    "status", "INGESTING",
                    "message", "Repository is being ingested, retry shortly"
            ));
        }

        // Serve shared file tree from DB via canonical
        List<FileNode> dbNodes = fileNodeRepository.findByCanonicalRepoIdOrderByPathAsc(canonical.getId());
        if (!dbNodes.isEmpty()) {
            log.info("Tree served from DB for repoId={} canonicalId={} nodes={}",
                    repoId, canonical.getId(), dbNodes.size());
            return ResponseEntity.ok(Map.of(
                    "source", "database",
                    "nodes", dbNodes.stream().map(FileNodeResponse::from).toList()
            ));
        }

        // Rare 2-second window: canonical exists but batch writer hasn't flushed yet
        return ResponseEntity.accepted().body(Map.of(
                "source", "pending",
                "status", "PROCESSING",
                "message", "Tree is being saved, retry in 2 seconds"
        ));
    }

    // --- Response Records ---

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
