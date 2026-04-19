package com.repomind.backend.domain.repo;

import com.repomind.backend.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Repo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String url;

    private String name;

    private String owner;

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
    private String status; // PENDING, INGESTING, READY, FAILED

    @Column(name = "error_msg")
    private String errorMsg;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}