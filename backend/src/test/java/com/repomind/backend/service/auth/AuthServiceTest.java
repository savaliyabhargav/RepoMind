package com.repomind.backend.service.auth;

import com.repomind.backend.domain.user.Plan;
import com.repomind.backend.domain.user.RefreshToken;
import com.repomind.backend.domain.user.RefreshTokenRepository;
import com.repomind.backend.domain.user.User;
import com.repomind.backend.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock GitHubService gitHubService;
    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock JwtService jwtService;

    @InjectMocks AuthService authService;

    // ── loginWithGitHub ───────────────────────────────────────────────────────

    @Test
    void loginWithGitHub_createsNewUserWhenNotFound() {
        when(gitHubService.getAccessToken("code123")).thenReturn(Mono.just("gh_token"));
        when(gitHubService.getGitHubProfile("gh_token")).thenReturn(Mono.just(Map.of(
                "id", 42L,
                "login", "newuser",
                "email", "new@example.com",
                "avatar_url", "https://avatars.com/u/42"
        )));
        when(userRepository.findByGithubId(42L)).thenReturn(Optional.empty());

        User savedUser = User.builder()
                .id(UUID.randomUUID()).githubId(42L).username("newuser")
                .email("new@example.com").plan(Plan.FREE).build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jwtService.generateToken(savedUser)).thenReturn("jwt_token");
        when(refreshTokenRepository.save(any())).thenReturn(mock(RefreshToken.class));

        var response = authService.loginWithGitHub("code123");

        assertThat(response.accessToken()).isEqualTo("jwt_token");
        assertThat(response.user()).isEqualTo(savedUser);
        assertThat(response.refreshToken()).isNotNull().hasSize(64);
    }

    @Test
    void loginWithGitHub_updatesExistingUserProfile() {
        when(gitHubService.getAccessToken("code")).thenReturn(Mono.just("token"));
        when(gitHubService.getGitHubProfile("token")).thenReturn(Mono.just(Map.of(
                "id", 99L,
                "login", "updatedname",
                "email", "updated@x.com",
                "avatar_url", "https://new-avatar.com"
        )));

        User existingUser = User.builder()
                .id(UUID.randomUUID()).githubId(99L).username("oldname")
                .email("old@x.com").plan(Plan.FREE).build();
        when(userRepository.findByGithubId(99L)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(existingUser)).thenReturn(existingUser);
        when(jwtService.generateToken(existingUser)).thenReturn("jwt");
        when(refreshTokenRepository.save(any())).thenReturn(mock(RefreshToken.class));

        authService.loginWithGitHub("code");

        assertThat(existingUser.getUsername()).isEqualTo("updatedname");
        assertThat(existingUser.getEmail()).isEqualTo("updated@x.com");
        assertThat(existingUser.getGithubToken()).isEqualTo("token");
    }

    @Test
    void loginWithGitHub_savesRefreshTokenAsHash() {
        when(gitHubService.getAccessToken("code")).thenReturn(Mono.just("ghToken"));
        when(gitHubService.getGitHubProfile("ghToken")).thenReturn(Mono.just(Map.of(
                "id", 1L, "login", "u", "email", "u@u.com", "avatar_url", "a"
        )));
        User user = User.builder().id(UUID.randomUUID()).githubId(1L).username("u").plan(Plan.FREE).build();
        when(userRepository.findByGithubId(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);
        when(jwtService.generateToken(any())).thenReturn("jwt");

        ArgumentCaptor<RefreshToken> rtCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        when(refreshTokenRepository.save(rtCaptor.capture())).thenReturn(mock(RefreshToken.class));

        var resp = authService.loginWithGitHub("code");

        RefreshToken saved = rtCaptor.getValue();
        // Verify stored hash matches SHA-256 of the returned raw token
        String expectedHash = sha256Base64(resp.refreshToken());
        assertThat(saved.getTokenHash()).isEqualTo(expectedHash);
        assertThat(saved.getRevoked()).isFalse();
        assertThat(saved.getExpiresAt()).isAfter(Instant.now().plus(6, ChronoUnit.DAYS));
    }

    // ── refreshToken ──────────────────────────────────────────────────────────

    @Test
    void refreshToken_rotatesToNewTokenAndReturnsNewJwt() {
        User user = User.builder().id(UUID.randomUUID()).githubId(2L).username("u").plan(Plan.FREE).build();
        String rawToken = "a".repeat(64);
        String hash = sha256Base64(rawToken);

        RefreshToken existing = RefreshToken.builder()
                .user(user).tokenHash(hash)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .revoked(false).build();

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(existing));
        when(jwtService.generateToken(user)).thenReturn("new_jwt");
        when(refreshTokenRepository.save(any())).thenReturn(mock(RefreshToken.class));

        var resp = authService.refreshToken(rawToken);

        assertThat(resp.accessToken()).isEqualTo("new_jwt");
        assertThat(resp.refreshToken()).isNotEqualTo(rawToken); // rotated
        verify(refreshTokenRepository).delete(existing);
    }

    @Test
    void refreshToken_throwsWhenTokenNotFound() {
        String rawToken = "b".repeat(64);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refreshToken(rawToken))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid refresh token");
    }

    @Test
    void refreshToken_throwsAndDeletesWhenExpired() {
        User user = User.builder().id(UUID.randomUUID()).githubId(3L).username("u").plan(Plan.FREE).build();
        String rawToken = "c".repeat(64);
        String hash = sha256Base64(rawToken);

        RefreshToken expired = RefreshToken.builder()
                .user(user).tokenHash(hash)
                .expiresAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .revoked(false).build();

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refreshToken(rawToken))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("expired or revoked");

        verify(refreshTokenRepository).delete(expired);
    }

    @Test
    void refreshToken_throwsAndDeletesWhenRevoked() {
        User user = User.builder().id(UUID.randomUUID()).githubId(4L).username("u").plan(Plan.FREE).build();
        String rawToken = "d".repeat(64);
        String hash = sha256Base64(rawToken);

        RefreshToken revoked = RefreshToken.builder()
                .user(user).tokenHash(hash)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .revoked(true).build();

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> authService.refreshToken(rawToken))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("expired or revoked");

        verify(refreshTokenRepository).delete(revoked);
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static String sha256Base64(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
