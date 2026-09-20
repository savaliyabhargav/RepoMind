CREATE TABLE analyses (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- repo_id / user_id are plain stored ids now — repos and users live in
    -- repo-ingestion-service's and auth-service's own separate databases.
    repo_id         UUID NOT NULL,
    user_id         UUID NOT NULL,
    ai_provider     VARCHAR(20) NOT NULL DEFAULT 'CLAUDE',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    current_stage   INT NOT NULL DEFAULT 0,
    scope_file_ids  UUID[],
    result          JSONB,
    error_msg       TEXT,
    tokens_used     INT DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_analyses_repo_id ON analyses(repo_id);
CREATE INDEX idx_analyses_user_id ON analyses(user_id);
CREATE INDEX idx_analyses_status ON analyses(status);
