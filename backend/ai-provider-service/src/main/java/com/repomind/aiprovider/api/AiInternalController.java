package com.repomind.aiprovider.api;

import com.repomind.aiprovider.AiProviderRouter;
import com.repomind.aiprovider.dto.AiEmbeddingRequest;
import com.repomind.aiprovider.dto.AiEmbeddingResponse;
import com.repomind.aiprovider.dto.AiGenerationRequest;
import com.repomind.aiprovider.dto.AiGenerationResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/ai")
public class AiInternalController {

    private final AiProviderRouter aiProviderRouter;

    public AiInternalController(AiProviderRouter aiProviderRouter) {
        this.aiProviderRouter = aiProviderRouter;
    }

    @PostMapping("/generate")
    public AiGenerationResponse generate(@RequestBody AiGenerationRequest request) {
        return aiProviderRouter.resolve(request.provider()).generate(request);
    }

    @PostMapping("/embed")
    public AiEmbeddingResponse embed(@RequestBody AiEmbeddingRequest request) {
        return aiProviderRouter.resolve(request.provider()).embed(request);
    }
}
