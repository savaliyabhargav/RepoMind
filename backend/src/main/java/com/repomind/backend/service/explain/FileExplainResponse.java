package com.repomind.backend.service.explain;

import java.util.List;

public record FileExplainResponse(
        String fileId,
        String path,
        String name,
        String language,
        Long sizeBytes,
        String roleSummary,
        String diagramType,
        String mermaidCode,
        String summary,
        List<String> concepts
) {}
