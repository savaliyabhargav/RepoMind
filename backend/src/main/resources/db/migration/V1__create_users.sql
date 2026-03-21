CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    github_id     BIGINT UNIQUE NOT NULL,
    email         VARCHAR(255),
    username      VARCHAR(100) NOT NULL,
    avatar_url    TEXT,
    github_token  TEXT,
    plan          VARCHAR(20) NOT NULL DEFAULT 'FREE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_github_id ON users(github_id);
CREATE INDEX idx_users_email ON users(email);