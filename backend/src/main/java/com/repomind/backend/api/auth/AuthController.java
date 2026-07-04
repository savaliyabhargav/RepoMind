package com.repomind.backend.api.auth;

import com.repomind.backend.service.auth.AuthService;
import com.repomind.backend.service.auth.AuthService.AuthResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/github")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request,
                                   HttpServletRequest httpRequest,
                                   HttpServletResponse response) {
        String code = request.get("code");
        AuthResponse authResponse = authService.loginWithGitHub(code);

        boolean isSecure = httpRequest.isSecure();
        Cookie cookie = new Cookie("refresh_token", authResponse.refreshToken());
        cookie.setHttpOnly(true);
        cookie.setSecure(isSecure);
        cookie.setPath("/");
        cookie.setMaxAge(7 * 24 * 60 * 60);
        cookie.setAttribute("SameSite", isSecure ? "Strict" : "Lax");

        response.addCookie(cookie);

        // Return the JWT and User data in the body
        return ResponseEntity.ok(Map.of(
                "accessToken", authResponse.accessToken(),
                "user", authResponse.user()
        ));
    }
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = "refresh_token") String rawRefreshToken,
                                     HttpServletRequest httpRequest,
                                     HttpServletResponse response) {
        try {
            AuthResponse authResponse = authService.refreshToken(rawRefreshToken);

            boolean isSecure = httpRequest.isSecure();
            // Set the NEW rotated refresh token in the cookie
            Cookie cookie = new Cookie("refresh_token", authResponse.refreshToken());
            cookie.setHttpOnly(true);
            cookie.setSecure(isSecure);
            cookie.setPath("/");
            cookie.setMaxAge(7 * 24 * 60 * 60);
            cookie.setAttribute("SameSite", isSecure ? "Strict" : "Lax");
            response.addCookie(cookie);

            return ResponseEntity.ok(Map.of(
                    "accessToken", authResponse.accessToken(),
                    "user", authResponse.user()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Session expired"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@CookieValue(name = "refresh_token", required = false) String rawRefreshToken,
                                    HttpServletRequest httpRequest,
                                    HttpServletResponse response) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            authService.logout(rawRefreshToken);
        }

        // Expire the cookie regardless of whether a token was present
        Cookie cookie = new Cookie("refresh_token", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(httpRequest.isSecure());
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }
}
