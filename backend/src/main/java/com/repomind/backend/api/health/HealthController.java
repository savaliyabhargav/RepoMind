package com.repomind.backend.api.health;

import com.repomind.backend.service.ai.AiProviderRouter;
import com.repomind.backend.service.ai.dto.AiGenerationRequest;
import com.repomind.backend.service.ai.dto.AiGenerationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final AiProviderRouter aiProviderRouter;
    private final String defaultAiProvider;

    public HealthController(
            AiProviderRouter aiProviderRouter,
            @Value("${app.ai.provider:NVIDIA_DEV}") String defaultAiProvider
    ) {
        this.aiProviderRouter = aiProviderRouter;
        this.defaultAiProvider = defaultAiProvider;
    }

    @GetMapping
    public Map<String, String> healthCheck() {
        return Map.of(
                "status", "UP",
                "message", "RepoMind Backend is secured and running"
        );
    }

    @GetMapping("/ai")
    public Map<String, Object> aiHealthCheck() {
        String provider = defaultAiProvider == null || defaultAiProvider.isBlank()
                ? "NVIDIA_DEV"
                : defaultAiProvider.trim().toUpperCase();

        try {
            AiGenerationRequest request = new AiGenerationRequest(
                    provider,
                    "meta/llama-3.1-70b-instruct",
                    "Reply with exactly OK",
                    "health-check",
                    0.0,
                    8
            );
            AiGenerationResponse response = aiProviderRouter.resolve(provider).generate(request);
            String reply = response.text() == null ? "" : response.text().trim();

            return Map.of(
                    "status", "UP",
                    "provider", provider,
                    "model", response.model(),
                    "reply", reply,
                    "inputTokens", response.usage().inputTokens(),
                    "outputTokens", response.usage().outputTokens()
            );
        } catch (Exception ex) {
            return Map.of(
                    "status", "DOWN",
                    "provider", provider,
                    "error", ex.getMessage() == null ? "Unknown AI provider error" : ex.getMessage()
            );
        }
    }
}
