package com.repomind.backend.api.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.backend.domain.repo.CanonicalRepo;
import com.repomind.backend.domain.repo.FileNodeRepository;
import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.domain.user.Plan;
import com.repomind.backend.domain.user.User;
import com.repomind.backend.domain.user.UserRepository;
import com.repomind.backend.service.ingestion.kafka.RepoIngestionProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepoControllerTest {

    @Mock RepoIngestionProducer repoIngestionProducer;
    @Mock RepoRepository repoRepository;
    @Mock FileNodeRepository fileNodeRepository;
    @Mock UserRepository userRepository;
    @Mock RedisTemplate<String, String> redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    @InjectMocks RepoController controller;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        // Inject real ObjectMapper via reflection since @InjectMocks uses constructor
        var field = RepoController.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(controller, objectMapper);

        userId = UUID.randomUUID();
        user = User.builder()
                .id(userId).githubId(1L).username("user")
                .email("u@u.com").avatarUrl("").plan(Plan.FREE).build();
    }

    // ── ingest — happy path (new repo) ────────────────────────────────────────

    @Test
    void ingest_newRepo_returnsAcceptedAndSubmitsToKafka() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(repoRepository.findByUrlAndUserId(anyString(), eq(userId))).thenReturn(Optional.empty());

        Repo saved = Repo.builder().id(UUID.randomUUID()).user(user)
                .url("https://github.com/owner/repo").status("PENDING").build();
        when(repoRepository.save(any())).thenReturn(saved);

        var resp = controller.ingest(Map.of(
                "url", "https://github.com/owner/repo",
                "userId", userId.toString()
        ));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(repoIngestionProducer).submitIngestion(eq(saved.getId()), anyString(), eq(userId));
    }

    // ── ingest — duplicate with canonical linked ──────────────────────────────

    @Test
    void ingest_duplicateWithCanonicalLinked_returns200WithoutRetrigger() {
        CanonicalRepo canonical = CanonicalRepo.builder().id(UUID.randomUUID())
                .owner("owner").repoName("repo").provider("GITHUB").status("READY").build();

        Repo existing = Repo.builder().id(UUID.randomUUID()).user(user)
                .url("https://github.com/owner/repo")
                .status("READY").canonicalRepo(canonical).build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(repoRepository.findByUrlAndUserId(anyString(), eq(userId))).thenReturn(Optional.of(existing));

        var resp = controller.ingest(Map.of(
                "url", "https://github.com/owner/repo",
                "userId", userId.toString()
        ));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(repoIngestionProducer);
    }

    // ── ingest — duplicate with canonical NULL + not INGESTING → re-trigger ──

    @Test
    void ingest_duplicateCanonicalNullNotIngesting_reTriggersIngestionAndReturnsAccepted() {
        Repo broken = Repo.builder().id(UUID.randomUUID()).user(user)
                .url("https://github.com/owner/repo")
                .status("FAILED").canonicalRepo(null).build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(repoRepository.findByUrlAndUserId(anyString(), eq(userId))).thenReturn(Optional.of(broken));
        when(repoRepository.save(broken)).thenReturn(broken);

        var resp = controller.ingest(Map.of(
                "url", "https://github.com/owner/repo",
                "userId", userId.toString()
        ));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(broken.getStatus()).isEqualTo("PENDING");
        assertThat(broken.getErrorMsg()).isNull();
        verify(repoIngestionProducer).submitIngestion(eq(broken.getId()), anyString(), eq(userId));
    }

    // ── ingest — duplicate with canonical NULL + currently INGESTING → wait ──

    @Test
    void ingest_duplicateCanonicalNullWhileIngesting_returns200WithoutRetrigger() {
        Repo ingesting = Repo.builder().id(UUID.randomUUID()).user(user)
                .url("https://github.com/owner/repo")
                .status("INGESTING").canonicalRepo(null).build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(repoRepository.findByUrlAndUserId(anyString(), eq(userId))).thenReturn(Optional.of(ingesting));

        var resp = controller.ingest(Map.of(
                "url", "https://github.com/owner/repo",
                "userId", userId.toString()
        ));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(repoIngestionProducer);
    }

    // ── ingest — validation ───────────────────────────────────────────────────

    @Test
    void ingest_missingUrl_returnsBadRequest() {
        var resp = controller.ingest(Map.of("userId", userId.toString()));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ingest_invalidGitHubUrl_returnsBadRequest() {
        // URL validation runs before user lookup — no repository call expected
        var resp = controller.ingest(Map.of(
                "url", "https://gitlab.com/owner/repo",
                "userId", userId.toString()
        ));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ingest_unknownUserId_returnsBadRequest() {
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        var resp = controller.ingest(Map.of(
                "url", "https://github.com/owner/repo",
                "userId", UUID.randomUUID().toString()
        ));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── getRepoTree — FAILED status → 422 ────────────────────────────────────

    @Test
    void getRepoTree_failedRepoWithNoCanonical_returns422WithFailedSource() {
        UUID repoId = UUID.randomUUID();
        Repo failed = Repo.builder().id(repoId).user(user)
                .status("FAILED").errorMsg("Rate limit exceeded").canonicalRepo(null).build();

        when(repoRepository.findById(repoId)).thenReturn(Optional.of(failed));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        var resp = controller.getRepoTree(repoId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("source", "failed");
        assertThat(body).containsEntry("status", "FAILED");
        assertThat(body.get("message")).asString().contains("Rate limit exceeded");
    }

    // ── getRepoTree — still pending ───────────────────────────────────────────

    @Test
    void getRepoTree_pendingRepo_returns202WithPendingSource() {
        UUID repoId = UUID.randomUUID();
        Repo pending = Repo.builder().id(repoId).user(user)
                .status("PENDING").canonicalRepo(null).build();

        when(repoRepository.findById(repoId)).thenReturn(Optional.of(pending));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        var resp = controller.getRepoTree(repoId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("source", "pending");
    }

    // ── getRepoTree — Redis cache hit ─────────────────────────────────────────

    @Test
    void getRepoTree_redisCacheHit_returns200WithCacheSource() throws Exception {
        UUID repoId = UUID.randomUUID();
        Repo repo = Repo.builder().id(repoId).user(user).status("READY").build();

        when(repoRepository.findById(repoId)).thenReturn(Optional.of(repo));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("repo:tree:" + repoId)).thenReturn("[]"); // empty list

        var resp = controller.getRepoTree(repoId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("source", "cache");
    }

    // ── getRepoTree — not found ───────────────────────────────────────────────

    @Test
    void getRepoTree_repoNotFound_returns404() {
        UUID repoId = UUID.randomUUID();
        when(repoRepository.findById(repoId)).thenReturn(Optional.empty());

        var resp = controller.getRepoTree(repoId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
