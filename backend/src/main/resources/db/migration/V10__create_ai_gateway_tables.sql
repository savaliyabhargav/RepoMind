CREATE TABLE prompt_templates (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) UNIQUE NOT NULL,
    system_prompt   TEXT NOT NULL,
    user_prompt     TEXT NOT NULL,
    provider        VARCHAR(20) NOT NULL DEFAULT 'CLAUDE',
    version         INT NOT NULL DEFAULT 1,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ai_call_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    analysis_id     UUID REFERENCES analyses(id) ON DELETE SET NULL,
    session_id      UUID REFERENCES chat_sessions(id) ON DELETE SET NULL,
    provider        VARCHAR(20) NOT NULL,
    model           VARCHAR(50) NOT NULL,
    input_tokens    INT DEFAULT 0,
    output_tokens   INT DEFAULT 0,
    duration_ms     BIGINT DEFAULT 0,
    success         BOOLEAN NOT NULL DEFAULT TRUE,
    error_msg       TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_call_logs_user_id ON ai_call_logs(user_id);
CREATE INDEX idx_ai_call_logs_analysis_id ON ai_call_logs(analysis_id);
CREATE INDEX idx_ai_call_logs_created_at ON ai_call_logs(created_at);