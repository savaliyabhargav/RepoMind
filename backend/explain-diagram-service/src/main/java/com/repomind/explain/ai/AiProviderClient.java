package com.repomind.explain.ai;

import com.repomind.explain.ai.dto.AiEmbeddingRequest;
import com.repomind.explain.ai.dto.AiEmbeddingResponse;
import com.repomind.explain.ai.dto.AiGenerationRequest;
import com.repomind.explain.ai.dto.AiGenerationResponse;

public interface AiProviderClient {
    boolean supports(String provider);

    AiGenerationResponse generate(AiGenerationRequest request);

    AiEmbeddingResponse embed(AiEmbeddingRequest request);
}
