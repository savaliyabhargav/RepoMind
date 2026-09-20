package com.repomind.aiprovider;

import com.repomind.aiprovider.dto.AiEmbeddingRequest;
import com.repomind.aiprovider.dto.AiEmbeddingResponse;
import com.repomind.aiprovider.dto.AiGenerationRequest;
import com.repomind.aiprovider.dto.AiGenerationResponse;

public interface AiProviderClient {
    boolean supports(String provider);

    AiGenerationResponse generate(AiGenerationRequest request);

    AiEmbeddingResponse embed(AiEmbeddingRequest request);
}
