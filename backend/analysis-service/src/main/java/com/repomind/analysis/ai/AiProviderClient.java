package com.repomind.analysis.ai;

import com.repomind.analysis.ai.dto.AiEmbeddingRequest;
import com.repomind.analysis.ai.dto.AiEmbeddingResponse;
import com.repomind.analysis.ai.dto.AiGenerationRequest;
import com.repomind.analysis.ai.dto.AiGenerationResponse;

public interface AiProviderClient {
    boolean supports(String provider);

    AiGenerationResponse generate(AiGenerationRequest request);

    AiEmbeddingResponse embed(AiEmbeddingRequest request);
}
