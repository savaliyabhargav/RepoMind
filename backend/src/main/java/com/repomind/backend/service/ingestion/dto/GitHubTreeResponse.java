package com.repomind.backend.service.ingestion.dto;

import java.util.List;

public record GitHubTreeResponse(
        String sha,
        String url,
        List<GitHubTreeEntry> tree,
        boolean truncated
) {}
