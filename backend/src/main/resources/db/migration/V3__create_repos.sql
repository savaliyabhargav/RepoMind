CREATE TABLE repos (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    url             TEXT NOT NULL,
    name            VARCHAR(255) NOT NULL,
    owner           VARCHAR(255) NOT NULL,
    provider        VARCHAR(20) NOT NULL DEFAULT 'GITHUB',
    is_private      BOOLEAN NOT NULL DEFAULT FALSE,
    default_branch  VARCHAR(100) DEFAULT 'main',
    file_count      INT DEFAULT 0,
    size_kb         BIGINT DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    error_msg       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_repos_user_id ON repos(user_id);
CREATE INDEX idx_repos_status ON repos(status);