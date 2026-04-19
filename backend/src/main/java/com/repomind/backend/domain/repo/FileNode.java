package com.repomind.backend.domain.repo;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "file_nodes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileNode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false)
    private Repo repo;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String path;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type; // FILE or DIRECTORY

    private Integer depth;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    private String language;

    @Column(name = "role_summary", columnDefinition = "TEXT")
    private String roleSummary;

    @Column(name = "embedding_id")
    private String embeddingId;

    @Builder.Default
    @Column(name = "is_in_scope")
    private boolean isInScope = true;
}
