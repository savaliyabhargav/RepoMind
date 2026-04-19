package com.repomind.backend.service.ingestion.dto;

public record GitHubTreeEntry(
        String path,
        String mode,
        String type,
        String sha,
        Long size // Changed from long to Long to allow nulls for folders
) {}
