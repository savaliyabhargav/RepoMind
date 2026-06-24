package com.repomind.backend.api.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.backend.domain.repo.FileNode;
import com.repomind.backend.domain.repo.FileNodeRepository;
import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.domain.user.User;
import com.repomind.backend.domain.user.UserRepository;
import com.repomind.backend.service.ingestion.kafka.FileNodeMessage;
import com.repomind.backend.service.ingestion.kafka.RepoIngestionProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern GITHUB_URL_PATTERN =
            Pattern.compile("github\\.com/([^/]+)/([^/.]+)(?:\\.git)?");

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody Map<String, String> request) {
        String url = request.get("url");
        String userIdStr = request.get("userId");

        if (url == null || userIdStr == null) {
            return ResponseEntity.badRequest()
                    .body("Missing 'url' or 'userId' in request body");
        }

        UUID userId = UUID.fromString(userIdStr);
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body("User not found");
        }

        // Extract owner and name from URL immediately — no GitHub call needed
        // e.g. "github.com/savaliyabhargav/RepoMind" → owner="savaliyabhargav", name="RepoMind"
        Matcher matcher = GITHUB_URL_PATTERN.matcher(url);
        if (!matcher.find()) {
            return ResponseEntity.badRequest().body("Invalid GitHub URL");
        }
        String owner = matcher.group(1);
        String name = matcher.group(2);

        // Create repo row immediately with PENDING status
        // name and owner come from URL parsing — GitHub will fill in the rest later
        Repo repo = Repo.builder()
                .user(user)
                .url(url)
                .name(name)
                .owner(owner)
                .provider("GITHUB")
                .status("PENDING")
                .build();
        repo = repoRepository.save(repo);

        // Write to Kafka — takes ~5ms, then this thread is completely free
        repoIngestionProducer.submitIngestion(repo.getId(), url, userId);

        // Return IMMEDIATELY — user does not wait for GitHub or DB writes
        return ResponseEntity.accepted().body(Map.of(
                "repoId", repo.getId(),
                "status", "PENDING",
                "message", "Repository ingestion started"
        ));
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

        // Step 1 — check Redis first (~1ms)
        // If the worker just finished fetching from GitHub, tree is here already
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

        // Step 2 — check DB (batch writer has already flushed by now)
        List<FileNode> dbNodes = fileNodeRepository.findByRepoIdOrderByPathAsc(repoId);
        if (!dbNodes.isEmpty()) {
            log.info("Tree served from DB for repoId={} nodes={}", repoId, dbNodes.size());
            return ResponseEntity.ok(Map.of(
                    "source", "database",
                    "nodes", dbNodes.stream().map(FileNodeResponse::from).toList()
            ));
        }

        // Step 3 — both Redis and DB are empty
        // This is the rare 2-second window between Redis deletion and DB write
        // Tell the frontend to retry shortly
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
