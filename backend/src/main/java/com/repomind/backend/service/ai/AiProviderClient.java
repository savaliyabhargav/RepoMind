package com.repomind.backend.service.ai;

import com.repomind.backend.service.ai.dto.AiEmbeddingRequest;
import com.repomind.backend.service.ai.dto.AiEmbeddingResponse;
import com.repomind.backend.service.ai.dto.AiGenerationRequest;
import com.repomind.backend.service.ai.dto.AiGenerationResponse;

public interface AiProviderClient {
    boolean supports(String provider);

    AiGenerationResponse generate(AiGenerationRequest request);

    AiEmbeddingResponse embed(AiEmbeddingRequest request);
}
