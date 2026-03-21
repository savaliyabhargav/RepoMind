CREATE TABLE analysis_stages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id     UUID NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    stage_number    INT NOT NULL,
    stage_name      VARCHAR(50) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    result          JSONB,
    tokens_used     INT DEFAULT 0,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,

    UNIQUE(analysis_id, stage_number)
);

CREATE INDEX idx_analysis_stages_analysis_id ON analysis_stages(analysis_id);