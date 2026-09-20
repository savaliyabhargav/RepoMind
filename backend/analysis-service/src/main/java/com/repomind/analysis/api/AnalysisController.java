package com.repomind.analysis.api;

import com.repomind.analysis.AnalysisPipelineService;
import com.repomind.analysis.AnalysisResponse;
import com.repomind.analysis.AnalysisStageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/analyses")
@Validated
public class AnalysisController {

    private final AnalysisPipelineService analysisPipelineService;

    public AnalysisController(AnalysisPipelineService analysisPipelineService) {
        this.analysisPipelineService = analysisPipelineService;
    }

    @PostMapping
    public ResponseEntity<AnalysisResponse> startAnalysis(@Valid @RequestBody StartAnalysisRequest request) {
        AnalysisResponse response = analysisPipelineService.runPipeline(
                request.repoId(),
                request.userId(),
                request.aiProvider()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{analysisId}")
    public ResponseEntity<AnalysisResponse> getAnalysis(@PathVariable UUID analysisId) {
        return ResponseEntity.ok(analysisPipelineService.getAnalysis(analysisId));
    }

    @GetMapping("/{analysisId}/stages")
    public ResponseEntity<List<AnalysisStageResponse>> getStages(@PathVariable UUID analysisId) {
        return ResponseEntity.ok(analysisPipelineService.getStages(analysisId));
    }

    public record StartAnalysisRequest(
            @NotNull UUID repoId,
            @NotNull UUID userId,
            String aiProvider
    ) {
    }
}

