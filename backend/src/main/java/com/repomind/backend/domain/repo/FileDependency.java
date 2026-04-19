package com.repomind.backend.domain.repo;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "file_node_dependencies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_file_id", nullable = false)
    private FileNode sourceFile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_file_id", nullable = false)
    private FileNode targetFile;

    @Column(name = "relationship_type", nullable = false)
    private String relationshipType; // IMPORTS, EXTENDS, IMPLEMENTS, CALLS, USES
}