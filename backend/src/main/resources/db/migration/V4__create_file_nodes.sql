CREATE TABLE file_nodes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_id         UUID NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    path            TEXT NOT NULL,
    name            VARCHAR(255) NOT NULL,
    type            VARCHAR(10) NOT NULL,
    depth           INT NOT NULL DEFAULT 0,
    size_bytes      BIGINT DEFAULT 0,
    language        VARCHAR(50),
    role_summary    TEXT,
    embedding_id    VARCHAR(100),
    is_in_scope     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE(repo_id, path)
);

CREATE INDEX idx_file_nodes_repo_id ON file_nodes(repo_id);
CREATE INDEX idx_file_nodes_language ON file_nodes(language);