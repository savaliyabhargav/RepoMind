package com.repomind.explain.api;

import com.repomind.explain.FileExplainResponse;
import com.repomind.explain.FileExplainService;
import com.repomind.explain.RepoOverviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/repo")
public class FileExplainController {

    private final FileExplainService fileExplainService;
    private final RepoOverviewService repoOverviewService;

    public FileExplainController(FileExplainService fileExplainService,
                                 RepoOverviewService repoOverviewService) {
        this.fileExplainService = fileExplainService;
        this.repoOverviewService = repoOverviewService;
    }

    @GetMapping("/{repoId}/files/{fileId}/explain")
    public ResponseEntity<FileExplainResponse> explain(
            @PathVariable UUID repoId,
            @PathVariable UUID fileId,
            @RequestParam(required = false, defaultValue = "LOCAL") String aiProvider,
            @RequestParam(required = false, defaultValue = "false") boolean refresh
    ) {
        return ResponseEntity.ok(fileExplainService.explain(repoId, fileId, aiProvider, refresh));
    }

    @GetMapping("/{repoId}/overview")
    public ResponseEntity<FileExplainResponse> overview(
            @PathVariable UUID repoId,
            @RequestParam(required = false, defaultValue = "LOCAL") String aiProvider,
            @RequestParam(required = false, defaultValue = "false") boolean refresh
    ) {
        return ResponseEntity.ok(repoOverviewService.overview(repoId, aiProvider, refresh));
    }
}
