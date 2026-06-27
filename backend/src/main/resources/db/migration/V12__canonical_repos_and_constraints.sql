-- B2: introduce canonical_repos so a single repo's file tree is stored once
--     regardless of how many users ingest it.
-- A1: unique constraint on (user_id, url) so a double-click never creates two rows.

-- 1. Create the shared canonical table
CREATE TABLE canonical_repos (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner           VARCHAR(255) NOT NULL,
    repo_name       VARCHAR(255) NOT NULL,
    provider        VARCHAR(20)  NOT NULL DEFAULT 'GITHUB',
    is_private      BOOLEAN      NOT NULL DEFAULT FALSE,
    default_branch  VARCHAR(100) DEFAULT 'main',
    file_count      INT          DEFAULT 0,
    size_kb         BIGINT       DEFAULT 0,
    status          VARCHAR(20)  NOT NULL DEFAULT 'FETCHING',
    last_fetched_at TIMESTAMPTZ,
    UNIQUE (owner, repo_name, provider)
);

-- 2. Wipe existing file data — file_nodes must be re-ingested
--    (development environment: no existing user data is at risk)
TRUNCATE file_node_dependencies;
TRUNCATE file_nodes;

-- 3. Swap file_nodes.repo_id → canonical_repo_id
DROP INDEX idx_file_nodes_repo_id;
ALTER TABLE file_nodes DROP COLUMN repo_id CASCADE;
ALTER TABLE file_nodes
    ADD COLUMN canonical_repo_id UUID NOT NULL REFERENCES canonical_repos(id) ON DELETE CASCADE;
ALTER TABLE file_nodes
    ADD CONSTRAINT uq_file_nodes_canonical_path UNIQUE (canonical_repo_id, path);
CREATE INDEX idx_file_nodes_canonical_repo_id ON file_nodes(canonical_repo_id);

-- 4. Link each user repo row to its canonical
ALTER TABLE repos
    ADD COLUMN canonical_repo_id UUID REFERENCES canonical_repos(id) ON DELETE SET NULL;

-- 5. A1 — prevent the same user from ingesting the same URL twice at the DB level
ALTER TABLE repos
    ADD CONSTRAINT uq_repos_user_url UNIQUE (user_id, url);
