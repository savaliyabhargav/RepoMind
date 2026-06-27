package com.repomind.backend.domain.repo;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "canonical_repos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CanonicalRepo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String owner;

    @Column(name = "repo_name", nullable = false)
    private String repoName;

    @Column(nullable = false)
    private String provider;

    @Column(name = "is_private")
    private boolean isPrivate;

    @Column(name = "default_branch")
    private String defaultBranch;

    @Column(name = "file_count")
    private Integer fileCount;

    @Column(name = "size_kb")
    private Long sizeKb;

    @Column(nullable = false)
    private String status; // FETCHING, READY

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;
}
