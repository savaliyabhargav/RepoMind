package com.repomind.backend.domain.ai;

import com.repomind.backend.domain.analysis.Analysis;
import com.repomind.backend.domain.user.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_call_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "analysis_id")
    private Analysis analysis;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Builder.Default
    @Column(name = "input_tokens")
    private Integer inputTokens = 0;

    @Builder.Default
    @Column(name = "output_tokens")
    private Integer outputTokens = 0;

    @Builder.Default
    @Column(name = "duration_ms")
    private Long durationMs = 0L;

    @Builder.Default
    @Column(nullable = false)
    private Boolean success = true;

    @Column(name = "error_msg")
    private String errorMsg;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
