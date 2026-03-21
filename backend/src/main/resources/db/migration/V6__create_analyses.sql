CREATE TABLE analyses (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_id         UUID NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
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