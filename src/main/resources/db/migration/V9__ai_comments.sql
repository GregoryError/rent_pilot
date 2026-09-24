-- V9: store AI pricing comments for dashboard display

CREATE TABLE ai_comments (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    property_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    comment TEXT NOT NULL,
    adjustments_count INT NOT NULL DEFAULT 0,
    context_summary VARCHAR(500)
);

CREATE INDEX idx_ai_comments_tenant_created ON ai_comments(tenant_id, created_at DESC);
