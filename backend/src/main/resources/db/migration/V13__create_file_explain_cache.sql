-- Server-side diagram cache: one row per (canonical repo, file path, content version).
-- Diagrams are deterministic enough at low temperature that a file version only
-- ever needs to be paid for once, across all users and sessions. The overview
-- diagram is stored under the reserved path '__repo_overview__' with the tree
-- fingerprint as its content hash.
CREATE TABLE file_explain_cache (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_repo_id UUID NOT NULL REFERENCES canonical_repos(id) ON DELETE CASCADE,
    path              TEXT NOT NULL,
    content_hash      VARCHAR(64) NOT NULL,
    provider          VARCHAR(32),
    model             VARCHAR(128),
    diagram_type      VARCHAR(64) NOT NULL,
    mermaid_code      TEXT NOT NULL,
    summary           TEXT,
    concepts_json     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_file_explain_cache UNIQUE (canonical_repo_id, path, content_hash)
);

CREATE INDEX idx_file_explain_cache_lookup
    ON file_explain_cache (canonical_repo_id, path);
