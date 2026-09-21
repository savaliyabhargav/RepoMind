package com.repomind.analysis.ai;

import com.repomind.analysis.ai.dto.AiEmbeddingRequest;
import com.repomind.analysis.ai.dto.AiEmbeddingResponse;
import com.repomind.analysis.ai.dto.AiGenerationRequest;
import com.repomind.analysis.ai.dto.AiGenerationResponse;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Forwards to ai-provider-service over HTTP instead of calling Groq/Nvidia/Gemini
 * directly. The remote service owns provider selection/failover, so this client
 * supports every provider name and just passes it through in the request body.
 */
@Service
public class RemoteAiProviderClient implements AiProviderClient {

    private final WebClient webClient;
    private final Duration timeout;

    public RemoteAiProviderClient(
            WebClient.Builder webClientBuilder,
            @Value("${app.ai-provider-service.base-url:http://localhost:8085}") String baseUrl,
            // Local/Kaggle models are slow and ai-provider-service may fail over across several
            // endpoints, so this must be well above the shared 60s WebClient default.
            @Value("${app.ai-provider-service.timeout-seconds:600}") long timeoutSeconds
    ) {
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(this.timeout);
        this.webClient = webClientBuilder.clone()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
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
                .timeout(timeout)
                .block();
    }

    @Override
    public AiEmbeddingResponse embed(AiEmbeddingRequest request) {
        return webClient.post()
                .uri("/internal/ai/embed")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiEmbeddingResponse.class)
                .timeout(timeout)
                .block();
    }
}
