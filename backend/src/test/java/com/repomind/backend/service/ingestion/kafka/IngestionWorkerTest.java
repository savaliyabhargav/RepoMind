package com.repomind.backend.service.ingestion.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.backend.domain.repo.CanonicalRepo;
import com.repomind.backend.domain.repo.CanonicalRepoRepository;
import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.domain.user.Plan;
import com.repomind.backend.domain.user.User;
import com.repomind.backend.service.ingestion.GitHubApiClient;
import com.repomind.backend.service.ingestion.GitHubRateLimitException;
import com.repomind.backend.service.ingestion.GitHubRateLimitTracker;
import com.repomind.backend.service.ingestion.GitHubTransientException;
import com.repomind.backend.service.ingestion.dto.GitHubRepoResponse;
import com.repomind.backend.service.ingestion.dto.GitHubTreeEntry;
import com.repomind.backend.service.ingestion.dto.GitHubTreeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionWorkerTest {

    @Mock GitHubApiClient gitHubApiClient;
    @Mock GitHubRateLimitTracker rateLimitTracker;
    @Mock CanonicalRepoRepository canonicalRepoRepository;
    @Mock RepoRepository repoRepository;
    @Mock KafkaTemplate<String, Object> fileNodeProducer;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock Acknowledgment ack;

    @InjectMocks IngestionWorker worker;

    private UUID repoId;
    private UUID userId;
    private Repo repo;
    private IngestRequestMessage message;
    private final String GITHUB_URL = "https://github.com/owner/myrepo";

    @BeforeEach
    void setUp() throws Exception {
        // Inject real ObjectMapper
        var field = IngestionWorker.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(worker, new ObjectMapper());

        repoId = UUID.randomUUID();
        userId = UUID.randomUUID();
        User user = User.builder().id(userId).githubId(1L).username("u").plan(Plan.FREE).build();
        repo = Repo.builder().id(repoId).user(user).url(GITHUB_URL).status("PENDING").build();
        message = new IngestRequestMessage(repoId, GITHUB_URL, userId, Instant.now().getEpochSecond());

        when(repoRepository.findById(repoId)).thenReturn(Optional.of(repo));
    }

    // ── happy path: new canonical ─────────────────────────────────────────────

    @Test
    void processIngestRequest_happyPath_marksRepoReadyAndPublishesNodes() throws Exception {
        CanonicalRepo canonical = CanonicalRepo.builder()
                .id(UUID.randomUUID()).owner("owner").repoName("myrepo")
                .provider("GITHUB").status("FETCHING").build();

        when(canonicalRepoRepository.findByOwnerAndRepoNameAndProvider("owner", "myrepo", "GITHUB"))
                .thenReturn(Optional.empty());
        when(canonicalRepoRepository.save(any())).thenReturn(canonical);

        GitHubRepoResponse repoInfo = new GitHubRepoResponse(
                1L, "myrepo", "owner/myrepo", false, "main", 100L, "Java", "desc");
        GitHubTreeResponse treeResp = new GitHubTreeResponse(
                "sha", "url",
                List.of(new GitHubTreeEntry("src/Main.java", "100644", "blob", "abc", 1024L)),
                false
        );

        when(gitHubApiClient.getRepoInfo("owner", "myrepo")).thenReturn(Mono.just(repoInfo));
        when(gitHubApiClient.getTree("owner", "myrepo", "HEAD")).thenReturn(Mono.just(treeResp));
        when(repoRepository.save(any())).thenReturn(repo);
        when(canonicalRepoRepository.save(canonical)).thenReturn(canonical);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        worker.processIngestRequest(message, ack);

        assertThat(repo.getStatus()).isEqualTo("READY");
        assertThat(repo.getCanonicalRepo()).isEqualTo(canonical);
        assertThat(canonical.getStatus()).isEqualTo("READY");
        verify(fileNodeProducer).send(eq("repo-filenodes-raw"), anyString(), any());
        verify(ack).acknowledge();
    }

    // ── fast path: canonical already READY ────────────────────────────────────

    @Test
    void processIngestRequest_canonicalAlreadyReady_skipsGitHubAndLinksRepo() {
        CanonicalRepo readyCanonical = CanonicalRepo.builder()
                .id(UUID.randomUUID()).owner("owner").repoName("myrepo")
                .provider("GITHUB").status("READY")
                .defaultBranch("main").fileCount(50).sizeKb(200L).build();

        when(canonicalRepoRepository.findByOwnerAndRepoNameAndProvider("owner", "myrepo", "GITHUB"))
                .thenReturn(Optional.of(readyCanonical));
        when(repoRepository.save(any())).thenReturn(repo);

        worker.processIngestRequest(message, ack);

        verifyNoInteractions(gitHubApiClient);
        assertThat(repo.getStatus()).isEqualTo("READY");
        assertThat(repo.getCanonicalRepo()).isEqualTo(readyCanonical);
        verify(ack).acknowledge();
    }

    // ── exception: generic → FAILED ──────────────────────────────────────────

    @Test
    void processIngestRequest_genericException_marksRepoFailedAndAcks() {
        CanonicalRepo canonical = CanonicalRepo.builder()
                .id(UUID.randomUUID()).owner("owner").repoName("myrepo")
                .provider("GITHUB").status("FETCHING").build();

        when(canonicalRepoRepository.findByOwnerAndRepoNameAndProvider(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(canonicalRepoRepository.save(any())).thenReturn(canonical);
        when(gitHubApiClient.getRepoInfo(any(), any()))
                .thenReturn(Mono.error(new RuntimeException("Network timeout")));
        when(gitHubApiClient.getTree(any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("Network timeout")));
        when(repoRepository.save(any())).thenReturn(repo);

        worker.processIngestRequest(message, ack);

        assertThat(repo.getStatus()).isEqualTo("FAILED");
        assertThat(repo.getErrorMsg()).contains("Network timeout");
        verify(ack).acknowledge();
    }

    // ── Redis: small repo is cached ───────────────────────────────────────────

    @Test
    void processIngestRequest_smallRepo_cachedInRedis() throws Exception {
        CanonicalRepo canonical = CanonicalRepo.builder()
                .id(UUID.randomUUID()).owner("owner").repoName("myrepo")
                .provider("GITHUB").status("FETCHING").build();

        when(canonicalRepoRepository.findByOwnerAndRepoNameAndProvider(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(canonicalRepoRepository.save(any())).thenReturn(canonical);

        // 10 file nodes — well under the 5000 threshold
        List<GitHubTreeEntry> entries = List.of(
                new GitHubTreeEntry("a.java", "100644", "blob", "sha1", 100L),
                new GitHubTreeEntry("b.java", "100644", "blob", "sha2", 200L)
        );
        GitHubRepoResponse repoInfo = new GitHubRepoResponse(
                1L, "myrepo", "owner/myrepo", false, "main", 10L, "Java", null);
        GitHubTreeResponse treeResp = new GitHubTreeResponse("sha", "url", entries, false);

        when(gitHubApiClient.getRepoInfo(any(), any())).thenReturn(Mono.just(repoInfo));
        when(gitHubApiClient.getTree(any(), any(), any())).thenReturn(Mono.just(treeResp));
        when(repoRepository.save(any())).thenReturn(repo);
        when(canonicalRepoRepository.save(canonical)).thenReturn(canonical);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        worker.processIngestRequest(message, ack);

        verify(valueOps).set(eq("repo:tree:" + repoId), anyString(), any());
    }

    // ── Redis: large repo is NOT cached ──────────────────────────────────────

    @Test
    void processIngestRequest_largeRepo_skipsRedisCache() throws Exception {
        CanonicalRepo canonical = CanonicalRepo.builder()
                .id(UUID.randomUUID()).owner("owner").repoName("myrepo")
                .provider("GITHUB").status("FETCHING").build();

        when(canonicalRepoRepository.findByOwnerAndRepoNameAndProvider(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(canonicalRepoRepository.save(any())).thenReturn(canonical);

        // Build 5001 entries — just above the threshold
        List<GitHubTreeEntry> entries = java.util.stream.IntStream.range(0, 5001)
                .mapToObj(i -> new GitHubTreeEntry("file" + i + ".java", "100644", "blob", "sha" + i, 10L))
                .toList();

        GitHubRepoResponse repoInfo = new GitHubRepoResponse(
                1L, "myrepo", "owner/myrepo", false, "main", 50000L, "Java", null);
        GitHubTreeResponse treeResp = new GitHubTreeResponse("sha", "url", entries, false);

        when(gitHubApiClient.getRepoInfo(any(), any())).thenReturn(Mono.just(repoInfo));
        when(gitHubApiClient.getTree(any(), any(), any())).thenReturn(Mono.just(treeResp));
        when(repoRepository.save(any())).thenReturn(repo);
        when(canonicalRepoRepository.save(canonical)).thenReturn(canonical);

        worker.processIngestRequest(message, ack);

        // Redis should NOT be written
        verifyNoInteractions(redisTemplate);
    }

    // ── transient retries exhausted → FAILED ─────────────────────────────────

    @Test
    void processIngestRequest_transientExceptionExhaustsRetries_marksRepoFailed() throws Exception {
        CanonicalRepo canonical = CanonicalRepo.builder()
                .id(UUID.randomUUID()).owner("owner").repoName("myrepo")
                .provider("GITHUB").status("FETCHING").build();

        when(canonicalRepoRepository.findByOwnerAndRepoNameAndProvider(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(canonicalRepoRepository.save(any())).thenReturn(canonical);

        GitHubTransientException transientEx = new GitHubTransientException("503 Service Unavailable");
        when(gitHubApiClient.getRepoInfo(any(), any())).thenReturn(Mono.error(transientEx));
        when(gitHubApiClient.getTree(any(), any(), any())).thenReturn(Mono.error(transientEx));
        when(repoRepository.save(any())).thenReturn(repo);

        // Mutate the array contents (not the reference — that's final) to avoid 90s sleep in CI
        var backoffField = IngestionWorker.class.getDeclaredField("TRANSIENT_BACKOFF_MS");
        backoffField.setAccessible(true);
        long[] backoffArr = (long[]) backoffField.get(null);
        long saved0 = backoffArr[0];
        long saved1 = backoffArr[1];
        backoffArr[0] = 0L;
        backoffArr[1] = 0L;

        try {
            worker.processIngestRequest(message, ack);
        } finally {
            backoffArr[0] = saved0;
            backoffArr[1] = saved1;
        }

        assertThat(repo.getStatus()).isEqualTo("FAILED");
        assertThat(repo.getErrorMsg()).contains("503");
        verify(ack).acknowledge();
        // MAX_TRANSIENT_ATTEMPTS = 3, so getRepoInfo is called exactly 3 times
        verify(gitHubApiClient, times(3)).getRepoInfo("owner", "myrepo");
    }

    // ── repo not found → ack and skip (throwing would redeliver forever) ─────

    @Test
    void processIngestRequest_repoNotFound_acksAndSkips() {
        when(repoRepository.findById(repoId)).thenReturn(Optional.empty());

        worker.processIngestRequest(message, ack);

        verify(ack).acknowledge();
        verifyNoInteractions(gitHubApiClient, canonicalRepoRepository, fileNodeProducer);
    }
}
