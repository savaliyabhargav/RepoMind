package com.repomind.explain.ai;

import com.repomind.explain.ai.dto.AiEmbeddingRequest;
import com.repomind.explain.ai.dto.AiEmbeddingResponse;
import com.repomind.explain.ai.dto.AiGenerationRequest;
import com.repomind.explain.ai.dto.AiGenerationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Forwards to ai-provider-service over HTTP instead of calling Groq/Nvidia/Gemini
 * directly. The remote service owns provider selection/failover, so this client
 * supports every provider name and just passes it through in the request body.
 */
@Service
public class RemoteAiProviderClient implements AiProviderClient {

    private final WebClient webClient;

    public RemoteAiProviderClient(
            WebClient.Builder webClientBuilder,
            @Value("${app.ai-provider-service.base-url:http://localhost:8085}") String baseUrl
    ) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public boolean supports(String provider) {
        return provider != null;
    }

    @Override
    public AiGenerationResponse generate(AiGenerationRequest request) {
        return webClient.post()
                .uri("/internal/ai/generate")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiGenerationResponse.class)
                .timeout(Duration.ofSeconds(90))
                .block();
    }

    @Override
    public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
        return webClient.post()
                .uri("/internal/ai/embed")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiEmbeddingResponse.class)
                .timeout(Duration.ofSeconds(90))
                .block();
    }
}
