CREATE TABLE chat_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    analysis_id     UUID NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    title           VARCHAR(255),
    mode            VARCHAR(30) NOT NULL DEFAULT 'SUBSYSTEM_EXPLAINER',
    message_count   INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_active     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_sessions_user_id ON chat_sessions(user_id);
CREATE INDEX idx_chat_sessions_analysis_id ON chat_sessions(analysis_id);