package com.repomind.backend.api.retrieval;

import com.repomind.backend.service.retrieval.RetrievalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/retrieval")
@Validated
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/index")
    public ResponseEntity<RetrievalService.IndexResult> indexRepo(@Valid @RequestBody IndexRequest request) {
        return ResponseEntity.ok(retrievalService.indexRepo(
                request.repoId(),
                request.userId(),
                request.aiProvider()
        ));
    }

    @PostMapping("/search")
    public ResponseEntity<RetrievalService.SearchResult> search(@Valid @RequestBody SearchRequest request) {
        return ResponseEntity.ok(retrievalService.search(
                request.repoId(),
                request.query(),
                request.topK() == null ? 8 : request.topK(),
                request.aiProvider()
        ));
    }

    public record IndexRequest(
            @NotNull UUID repoId,
            @NotNull UUID userId,
            String aiProvider
    ) {
    }

    public record SearchRequest(
            @NotNull UUID repoId,
            @NotBlank String query,
            Integer topK,
            String aiProvider
    ) {
    }
}

