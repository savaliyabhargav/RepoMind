package com.repomind.backend.service.ingestion.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.backend.domain.repo.CanonicalRepo;
import com.repomind.backend.domain.repo.CanonicalRepoRepository;
import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.service.ingestion.GitHubApiClient;
import com.repomind.backend.service.ingestion.GitHubRateLimitException;
import com.repomind.backend.service.ingestion.GitHubRateLimitTracker;
import com.repomind.backend.service.ingestion.GitHubTransientException;
import com.repomind.backend.service.ingestion.GitHubUrlParser;
import com.repomind.backend.service.ingestion.dto.GitHubRepoResponse;
import com.repomind.backend.service.ingestion.dto.GitHubTreeEntry;
import com.repomind.backend.service.ingestion.dto.GitHubTreeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionWorker {

    private final GitHubApiClient gitHubApiClient;
    private final GitHubRateLimitTracker rateLimitTracker;
    private final CanonicalRepoRepository canonicalRepoRepository;
    private final RepoRepository repoRepository;
    // Repos above this file count skip the Redis tree cache — serializing 50MB+ of JSON
    // on the worker thread is slower than just letting the frontend wait one flush cycle (~2s)
    private static final int REDIS_CACHE_MAX_NODES = 5000;

    private final KafkaTemplate<String, Object> fileNodeProducer;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

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
            repo.setStatus("INGESTING");
            repoRepository.save(repo);

            String owner = GitHubUrlParser.extractOwner(message.url());
            String repoName = GitHubUrlParser.extractRepoName(message.url());

            // Find existing canonical or create one — assigned exactly once (effectively final)
            final CanonicalRepo canonical = resolveCanonical(owner, repoName);

            if ("READY".equals(canonical.getStatus())) {
                // File tree already exists in DB — skip GitHub entirely
                linkRepoToCanonical(repo, canonical);
                log.info("Canonical already READY for {}/{} — skipped GitHub, repoId={} marked READY",
                        owner, repoName, message.repoId());
                ack.acknowledge();
                return;
            }

            // canonical is FETCHING — we are the first worker for this repo
            var githubResult = fetchFromGitHub(owner, repoName, message.repoId());
            GitHubRepoResponse info = githubResult.getT1();
            GitHubTreeResponse tree = githubResult.getT2();

            if (tree.truncated()) {
                log.warn("Truncated tree for {}/{} — repo exceeds 100k files", owner, repoName);
            }

            List<FileNodeMessage> fileNodes = tree.tree().stream()
                    .map(entry -> buildFileNodeMessage(canonical.getId(), message.repoId(), entry))
                    .toList();

            // Update canonical with real GitHub data and mark it READY
            canonical.setRepoName(info.name());
            canonical.setPrivate(info.isPrivate());
            canonical.setDefaultBranch(info.defaultBranch());
            canonical.setSizeKb(info.size());
            canonical.setFileCount(fileNodes.size());
            canonical.setStatus("READY");
            canonical.setLastFetchedAt(Instant.now());
            canonicalRepoRepository.save(canonical);

            // Cache tree in Redis for small repos — instant display for the frontend.
            // Large repos skip this: serializing 80k nodes to JSON is slow and wastes memory.
            // The frontend gets "pending" and retries; the first DB flush (~2s) serves the tree.
            String redisKey = "repo:tree:" + message.repoId();
            if (fileNodes.size() <= REDIS_CACHE_MAX_NODES) {
                redisTemplate.opsForValue().set(
                        redisKey, objectMapper.writeValueAsString(fileNodes), Duration.ofHours(1));
                log.info("Tree cached in Redis for repoId={} nodes={}", message.repoId(), fileNodes.size());
            } else {
                log.info("Skipping Redis cache for repoId={} — {} nodes exceeds threshold {}, tree will serve from DB",
                        message.repoId(), fileNodes.size(), REDIS_CACHE_MAX_NODES);
            }

            // Link user's repo to canonical and mark READY
            linkRepoToCanonical(repo, canonical);
            log.info("Repo marked READY repoId={}", message.repoId());

            // Publish file nodes to Kafka for background batch DB write
            fileNodes.forEach(node ->
                    fileNodeProducer.send("repo-filenodes-raw", canonical.getId().toString(), node));
            log.info("Published {} file nodes to Kafka for batch DB write", fileNodes.size());

            ack.acknowledge();

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Worker interrupted for repoId={}", message.repoId(), ex);
            repo.setStatus("FAILED");
            repo.setErrorMsg("Worker interrupted");
            repoRepository.save(repo);
            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Ingestion failed for repoId={}", message.repoId(), ex);
            repo.setStatus("FAILED");
            repo.setErrorMsg(ex.getMessage());
            repoRepository.save(repo);
            ack.acknowledge();
        }
    }

    /**
     * Returns the canonical row for this repo, creating it if it doesn't exist yet.
     * Always returns exactly one non-null CanonicalRepo, assigned once so it is
     * effectively final for use in lambdas.
     *
     * On a race between two workers: the unique constraint (owner, repo_name, provider)
     * causes one INSERT to fail — the loser fetches what the winner created.
     */
    private CanonicalRepo resolveCanonical(String owner, String repoName) {
        return canonicalRepoRepository
                .findByOwnerAndRepoNameAndProvider(owner, repoName, "GITHUB")
                .orElseGet(() -> {
                    try {
                        return canonicalRepoRepository.save(CanonicalRepo.builder()
                                .owner(owner)
                                .repoName(repoName)
                                .provider("GITHUB")
                                .status("FETCHING")
                                .build());
                    } catch (DataIntegrityViolationException ex) {
                        // Another worker inserted first — fetch theirs
                        return canonicalRepoRepository
                                .findByOwnerAndRepoNameAndProvider(owner, repoName, "GITHUB")
                                .orElseThrow(() -> new IllegalStateException(
                                        "Canonical missing after constraint race for " + owner + "/" + repoName));
                    }
                });
    }

    private void linkRepoToCanonical(Repo repo, CanonicalRepo canonical) {
        repo.setCanonicalRepo(canonical);
        repo.setName(canonical.getRepoName());
        repo.setOwner(canonical.getOwner());
        repo.setProvider("GITHUB");
        repo.setPrivate(canonical.isPrivate());
        repo.setDefaultBranch(canonical.getDefaultBranch());
        repo.setSizeKb(canonical.getSizeKb());
        repo.setFileCount(canonical.getFileCount());
        repo.setStatus("READY");
        repoRepository.save(repo);
    }

    // 3 attempts total: 1 initial + 2 retries at 30s and 60s
    private static final int MAX_TRANSIENT_ATTEMPTS = 3;
    private static final long[] TRANSIENT_BACKOFF_MS = {30_000L, 60_000L};

    /**
     * Calls GitHub for repo metadata and file tree with two layers of retry:
     *
     * - Rate limit (GitHubRateLimitException): sleeps until the window resets, then
     *   loops back. Does not count against the transient retry budget — the wait is
     *   bounded by GitHub's reset timestamp, not by us.
     *
     * - Transient error (GitHubTransientException): 5xx responses and network failures
     *   (connection refused, timeout, DNS error). Retried up to MAX_TRANSIENT_ATTEMPTS
     *   times with a fixed backoff. After all attempts are exhausted the exception
     *   propagates — the outer catch marks the repo FAILED and acks Kafka.
     *
     * - Permanent error (WebClientResponseException 4xx): repo not found, bad credentials.
     *   Not caught here — propagates immediately to the outer catch.
     */
    private reactor.util.function.Tuple2<GitHubRepoResponse, GitHubTreeResponse> fetchFromGitHub(
            String owner, String repoName, UUID repoId) throws InterruptedException {

        int transientAttempts = 0;

        while (true) {
            rateLimitTracker.awaitCapacityIfNeeded();

            try {
                return Mono.zip(
                        gitHubApiClient.getRepoInfo(owner, repoName),
                        gitHubApiClient.getTree(owner, repoName, "HEAD")
                ).block();

            } catch (GitHubRateLimitException ex) {
                long sleepMs = Math.max(0, ex.getResetEpochSeconds() - Instant.now().getEpochSecond() + 2) * 1000L;
                log.warn("Rate limited for repoId={}. Sleeping {}ms until window resets.", repoId, sleepMs);
                Thread.sleep(sleepMs);
                // Does not increment transientAttempts — rate limit wait is deterministic

            } catch (GitHubTransientException ex) {
                transientAttempts++;
                if (transientAttempts >= MAX_TRANSIENT_ATTEMPTS) {
                    log.error("GitHub still unavailable after {} attempts for repoId={} — giving up: {}",
                            MAX_TRANSIENT_ATTEMPTS, repoId, ex.getMessage());
                    throw ex;
                }
                long backoffMs = TRANSIENT_BACKOFF_MS[transientAttempts - 1];
                log.warn("GitHub transient error (attempt {}/{}) for repoId={}: {}. Retrying in {}ms.",
                        transientAttempts, MAX_TRANSIENT_ATTEMPTS, repoId, ex.getMessage(), backoffMs);
                Thread.sleep(backoffMs);
            }
        }
    }

    private FileNodeMessage buildFileNodeMessage(UUID canonicalRepoId, UUID userRepoId, GitHubTreeEntry entry) {
        String name = entry.path().substring(entry.path().lastIndexOf('/') + 1);
        int depth = (int) entry.path().chars().filter(ch -> ch == '/').count();
        String type = "blob".equals(entry.type()) ? "FILE" : "DIRECTORY";
        return new FileNodeMessage(canonicalRepoId, userRepoId, entry.path(), name, type, depth, entry.size());
    }
}
