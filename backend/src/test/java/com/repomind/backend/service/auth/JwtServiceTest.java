package com.repomind.backend.service.auth;

import com.repomind.backend.domain.user.Plan;
import com.repomind.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;
    private User testUser;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();

        jwtService = new JwtService(
                (RSAPrivateKey) pair.getPrivate(),
                (RSAPublicKey) pair.getPublic()
        );

        testUser = User.builder()
                .id(UUID.randomUUID())
                .githubId(12345L)
                .username("testuser")
                .email("test@example.com")
                .plan(Plan.FREE)
                .build();
    }

    // ── generateToken ─────────────────────────────────────────────────────────

    @Test
    void generateToken_returnsNonNullToken() {
        String token = jwtService.generateToken(testUser);
        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    void generateToken_tokenIsValidImmediately() {
        String token = jwtService.generateToken(testUser);
        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    @Test
    void generateToken_subjectIsUserUuid() {
        String token = jwtService.generateToken(testUser);
        String extracted = jwtService.extractUserUuid(token);
        assertThat(extracted).isEqualTo(testUser.getId().toString());
    }

    @Test
    void generateToken_differentUsersGetDifferentTokens() {
        User other = User.builder()
                .id(UUID.randomUUID())
                .githubId(99999L)
                .username("other")
                .plan(Plan.FREE)
                .build();

        String t1 = jwtService.generateToken(testUser);
        String t2 = jwtService.generateToken(other);
        assertThat(t1).isNotEqualTo(t2);
    }

    // ── isTokenValid ──────────────────────────────────────────────────────────

    @Test
    void isTokenValid_returnsFalseForGarbageToken() {
        assertThat(jwtService.isTokenValid("not.a.jwt")).isFalse();
    }

    @Test
    void isTokenValid_returnsFalseForTamperedPayload() {
        String token = jwtService.generateToken(testUser);
        // Flip one character in the payload section
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "X" + "." + parts[2];
        assertThat(jwtService.isTokenValid(tampered)).isFalse();
    }

    @Test
    void isTokenValid_returnsFalseForTokenSignedByDifferentKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair otherPair = gen.generateKeyPair();
        JwtService otherService = new JwtService(
                (RSAPrivateKey) otherPair.getPrivate(),
                (RSAPublicKey) otherPair.getPublic()
        );

        String tokenFromOtherKey = otherService.generateToken(testUser);
        // Our service's public key cannot verify a token signed by the other private key
        assertThat(jwtService.isTokenValid(tokenFromOtherKey)).isFalse();
    }

    // ── extractUserUuid ───────────────────────────────────────────────────────

    @Test
    void extractUserUuid_roundTripsCorrectly() {
        UUID userId = UUID.randomUUID();
        testUser = User.builder()
                .id(userId)
                .githubId(1L)
                .username("u")
                .plan(Plan.FREE)
                .build();
        String token = jwtService.generateToken(testUser);
        assertThat(jwtService.extractUserUuid(token)).isEqualTo(userId.toString());
    }
}
