package com.repomind.backend.service.auth;

import com.repomind.backend.domain.user.Plan;
import com.repomind.backend.domain.user.RefreshToken;
import com.repomind.backend.domain.user.RefreshTokenRepository;
import com.repomind.backend.domain.user.User;
import com.repomind.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final GitHubService gitHubService;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse loginWithGitHub(String code) {
        // 1. Get Access Token from GitHub
        String accessToken = gitHubService.getAccessToken(code).block();

        // 2. Get User Profile from GitHub
        Map<String, Object> profile = gitHubService.getGitHubProfile(accessToken).block();

        Long githubId = ((Number) profile.get("id")).longValue();
        String username = (String) profile.get("login");
        String email = (String) profile.get("email");
        String avatarUrl = (String) profile.get("avatar_url");

        // 3. Find or Create User in Postgres
        User user = userRepository.findByGithubId(githubId)
                .map(existingUser -> {
                    existingUser.setUsername(username);
                    existingUser.setEmail(email);
                    existingUser.setAvatarUrl(avatarUrl);
                    existingUser.setGithubToken(accessToken); // Store for repo ingestion later
                    return userRepository.save(existingUser);
                })
                .orElseGet(() -> userRepository.save(User.builder()
                        .githubId(githubId)
                        .username(username)
                        .email(email)
                        .avatarUrl(avatarUrl)
                        .githubToken(accessToken)
                        .plan(Plan.FREE)
                        .build()));

        // 4. Generate JWT
        String jwt = jwtService.generateToken(user);

        // 5. Generate and Save Refresh Token (Hashed)
        String rawRefreshToken = generateRandomString(64);
        saveRefreshToken(user, rawRefreshToken);

        return new AuthResponse(jwt, rawRefreshToken, user);
    }

    private void saveRefreshToken(User user, String rawToken) {
        String hashedToken = hashToken(rawToken);
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(hashedToken)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .revoked(false)
                .build();
        refreshTokenRepository.save(refreshToken);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error hashing token", e);
        }
    }

    private String generateRandomString(int length) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, length);
    }

    @Transactional
    public AuthResponse refreshToken(String rawRefreshToken) {
        // 1. Hash the incoming raw token to find it in the DB
        String hashedToken = hashToken(rawRefreshToken);

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(hashedToken)
                .orElseThrow(() -> new RuntimeException("Invalid refresh token"));

        // 2. Security Check: Is it revoked or expired?
        if (refreshToken.getRevoked() || refreshToken.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw new RuntimeException("Refresh token expired or revoked");
        }

        User user = refreshToken.getUser();

        // 3. Rotation: Delete the old token immediately (prevents replay attacks)
        refreshTokenRepository.delete(refreshToken);

        // 4. Issue new credentials
        String newJwt = jwtService.generateToken(user);
        String newRawRefreshToken = generateRandomString(64);
        saveRefreshToken(user, newRawRefreshToken);

        return new AuthResponse(newJwt, newRawRefreshToken, user);
    }

    // Simple DTO for the response
    public record AuthResponse(String accessToken, String refreshToken, User user) {}
}
