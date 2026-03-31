package com.repomind.backend.api.auth;

import com.repomind.backend.service.auth.AuthService;
import com.repomind.backend.service.auth.AuthService.AuthResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/github")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request, HttpServletResponse response) {
        String code = request.get("code");
        AuthResponse authResponse = authService.loginWithGitHub(code);

        // Set the Refresh Token in a secure, HttpOnly cookie
        Cookie cookie = new Cookie("refresh_token", authResponse.refreshToken());
        cookie.setHttpOnly(true);
        cookie.setSecure(true); // Should be true in production/Docker
        cookie.setPath("/");
        cookie.setMaxAge(7 * 24 * 60 * 60); // 7 days
        cookie.setAttribute("SameSite", "Strict");

        response.addCookie(cookie);

        // Return the JWT and User data in the body
        return ResponseEntity.ok(Map.of(
                "accessToken", authResponse.accessToken(),
                "user", authResponse.user()
        ));
    }
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = "refresh_token") String rawRefreshToken, HttpServletResponse response) {
        try {
            AuthResponse authResponse = authService.refreshToken(rawRefreshToken);

            // Set the NEW rotated refresh token in the cookie
            Cookie cookie = new Cookie("refresh_token", authResponse.refreshToken());
            cookie.setHttpOnly(true);
            cookie.setSecure(true);
            cookie.setPath("/");
            cookie.setMaxAge(7 * 24 * 60 * 60);
            cookie.setAttribute("SameSite", "Strict");
            response.addCookie(cookie);

            return ResponseEntity.ok(Map.of(
                    "accessToken", authResponse.accessToken(),
                    "user", authResponse.user()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Session expired"));
        }
    }
}
