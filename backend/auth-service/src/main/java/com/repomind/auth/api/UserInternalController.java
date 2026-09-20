package com.repomind.auth.api;

import com.repomind.auth.domain.User;
import com.repomind.auth.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service endpoints. Other services no longer read the users table
 * directly (it now lives in this service's own database) — they call this
 * instead. No auth on these routes: they're only reachable on the internal
 * docker network, matching the rest of this project's SecurityConfig, which
 * is permitAll everywhere anyway.
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class UserInternalController {

    private final UserRepository userRepository;

    @GetMapping("/{userId}")
    public ResponseEntity<UserSummary> getUser(@PathVariable UUID userId) {
        return userRepository.findById(userId)
                .map(UserSummary::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{userId}/github-token")
    public ResponseEntity<Map<String, String>> getGithubToken(@PathVariable UUID userId) {
        return userRepository.findById(userId)
                .map(user -> ResponseEntity.ok(Map.of("githubToken",
                        user.getGithubToken() == null ? "" : user.getGithubToken())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record UserSummary(UUID id, String username, String avatarUrl, String plan) {
        static UserSummary from(User user) {
            return new UserSummary(user.getId(), user.getUsername(), user.getAvatarUrl(), user.getPlan().name());
        }
    }
}
