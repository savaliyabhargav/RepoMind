package com.repomind.backend.service.ingestion.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.service.ingestion.GitHubApiClient;
import com.repomind.backend.service.ingestion.dto.GitHubRepoResponse;
import com.repomind.backend.service.ingestion.dto.GitHubTreeEntry;
import com.repomind.backend.service.ingestion.dto.GitHubTreeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionWorker {

    private final GitHubApiClient gitHubApiClient;
    private final RepoRepository repoRepository;
    private final KafkaTemplate<String, Object> fileNodeProducer;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final Pattern GITHUB_URL_PATTERN =
            Pattern.compile("github\\.com/([^/]+)/([^/.]+)(?:\\.git)?");

    // concurrency = "3" means 3 worker threads run in parallel, one per partition
    @KafkaListener(
            topics = "repo-ingest-requests",
            groupId = "ingestion-workers",
            concurrency = "3"
    )
    public void processIngestRequest(IngestRequestMessage message, Acknowledgment ack) {
        log.info("Worker picked up repoId={} url={}", message.repoId(), message.url());

        Repo repo = repoRepository.findById(message.repoId())
                .orElseThrow(() -> new IllegalStateException("Repo not found: " + message.repoId()));

        try {
            // Step 1 — mark as INGESTING so frontend knows work has started
            repo.setStatus("INGESTING");
            repoRepository.save(repo);

            // Step 2 — parse URL to get owner and repo name
            String owner = extractOwner(message.url());
            String repoName = extractRepoName(message.url());

            // Step 3 — call GitHub for repo metadata AND file tree IN PARALLEL
            // "HEAD" always resolves to the default branch, so no need to wait for getRepoInfo first
            // Both HTTP calls fire simultaneously; we wait for whichever finishes last (~1s vs ~2s sequential)
            var githubResult = Mono.zip(
                    gitHubApiClient.getRepoInfo(owner, repoName),
                    gitHubApiClient.getTree(owner, repoName, "HEAD")
            ).block();

            GitHubRepoResponse info = githubResult.getT1();
            GitHubTreeResponse tree = githubResult.getT2();

            List<FileNodeMessage> fileNodes = tree.tree().stream()
                    .map(entry -> buildFileNodeMessage(message.repoId(), entry))
                    .toList();

            // Step 5 — write tree to Redis IMMEDIATELY so frontend can show it right now
            // This is before DB write — that's intentional, user should not wait
            String redisKey = "repo:tree:" + message.repoId();
            String treeJson = objectMapper.writeValueAsString(fileNodes);
            redisTemplate.opsForValue().set(redisKey, treeJson, Duration.ofHours(1));
            log.info("Tree cached in Redis for repoId={} nodes={}", message.repoId(), fileNodes.size());

            // Step 6 — mark repo as READY immediately
            // Frontend will see this and fetch the tree from Redis
            repo.setName(info.name());
            repo.setOwner(owner);
            repo.setProvider("GITHUB");
            repo.setPrivate(info.isPrivate());
            repo.setDefaultBranch(info.defaultBranch());
            repo.setSizeKb(info.size());
            repo.setFileCount(fileNodes.size());
            repo.setStatus("READY");
            repoRepository.save(repo);
            log.info("Repo marked READY repoId={}", message.repoId());

            // Step 7 — push file nodes to Kafka for background batch DB write
            // This happens silently — user is already seeing the tree from Redis
            fileNodes.forEach(node ->
                    fileNodeProducer.send("repo-filenodes-raw",
                            message.repoId().toString(), node)
            );
            log.info("Published {} file nodes to Kafka for batch DB write", fileNodes.size());

            // Step 8 — tell Kafka this message was processed successfully
            // Only now will Kafka remove it from the queue
            ack.acknowledge();

        } catch (Exception ex) {
            log.error("Ingestion failed for repoId={}", message.repoId(), ex);
            repo.setStatus("FAILED");
            repo.setErrorMsg(ex.getMessage());
            repoRepository.save(repo);
            // Acknowledge anyway so Kafka does not redeliver the same broken message forever
            ack.acknowledge();
        }
    }

    private FileNodeMessage buildFileNodeMessage(java.util.UUID repoId, GitHubTreeEntry entry) {
        String name = entry.path().substring(entry.path().lastIndexOf('/') + 1);
        int depth = (int) entry.path().chars().filter(ch -> ch == '/').count();
        String type = "blob".equals(entry.type()) ? "FILE" : "DIRECTORY";
        return new FileNodeMessage(repoId, entry.path(), name, type, depth, entry.size());
    }

    private String extractOwner(String url) {
        Matcher m = GITHUB_URL_PATTERN.matcher(url);
        if (!m.find()) throw new IllegalArgumentException("Invalid GitHub URL: " + url);
        return m.group(1);
    }

    private String extractRepoName(String url) {
        Matcher m = GITHUB_URL_PATTERN.matcher(url);
        if (!m.find()) throw new IllegalArgumentException("Invalid GitHub URL: " + url);
        return m.group(2);
    }
}
