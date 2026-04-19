package com.repomind.backend.service.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GitHubRepoResponse(
        Long id,
        String name,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("private") boolean isPrivate,
        @JsonProperty("default_branch") String defaultBranch,
        Long size, // Changed to Long
        String language,
        String description
) {}
