package com.repomind.explain.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_explain_cache", uniqueConstraints = @UniqueConstraint(
        name = "uq_file_explain_cache",
        columnNames = {"canonical_repo_id", "path", "content_hash"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileExplainCache {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "canonical_repo_id", nullable = false)
    private UUID canonicalRepoId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String path;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(length = 32)
    private String provider;

    @Column(length = 128)
    private String model;

    @Column(name = "diagram_type", nullable = false, length = 64)
    private String diagramType;

    @Column(name = "mermaid_code", nullable = false, columnDefinition = "TEXT")
    private String mermaidCode;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "concepts_json", columnDefinition = "TEXT")
    private String conceptsJson;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
