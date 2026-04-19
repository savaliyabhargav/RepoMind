package com.repomind.backend.domain.analysis;

import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "analyses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Analysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_id", nullable = false)
    private Repo repo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "ai_provider")
    private String aiProvider; // CLAUDE or OPENAI

    @Column(nullable = false)
    private String status; // PENDING, RUNNING, COMPLETED, FAILED

    @Builder.Default
    @Column(name = "current_stage")
    private Integer currentStage = 0;

    // Fixed: Mapping for native PostgreSQL UUID[] array
    @Column(name = "scope_file_ids", columnDefinition = "uuid[]")
    private List<UUID> scopeFileIds;

    // This one stays as JSON because the 'result' column IS a jsonb blob
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    @Builder.Default
    @Column(name = "tokens_used")
    private Integer tokensUsed = 0;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}