package com.repomind.backend.api.repo;

import com.repomind.backend.service.explain.FileExplainResponse;
import com.repomind.backend.service.explain.FileExplainService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/repo")
public class FileExplainController {

    private final FileExplainService fileExplainService;

    public FileExplainController(FileExplainService fileExplainService) {
        this.fileExplainService = fileExplainService;
    }

    @GetMapping("/{repoId}/files/{fileId}/explain")
    public ResponseEntity<FileExplainResponse> explain(
            @PathVariable UUID repoId,
            @PathVariable UUID fileId,
            @RequestParam(required = false, defaultValue = "NVIDIA_DEV") String aiProvider
    ) {
        return ResponseEntity.ok(fileExplainService.explain(repoId, fileId, aiProvider));
    }
}
