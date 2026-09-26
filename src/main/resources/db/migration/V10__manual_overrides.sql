-- V10: manual overrides — user directives from AI chat that affect autopilot

CREATE TABLE manual_overrides (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    property_id BIGINT REFERENCES properties(id),

    override_type VARCHAR(50) NOT NULL,
    -- price_multiplier | min_stay_override | open_ahead_days |
    -- autopilot_mode | autopilot_interval | floor_ceil | close_dates

    params_json JSONB NOT NULL,
    description TEXT,

    active BOOLEAN NOT NULL DEFAULT TRUE,
    origin VARCHAR(50) NOT NULL DEFAULT 'ai_chat',
    created_by_message_id BIGINT REFERENCES chat_messages(id),

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP,
    cancelled_at TIMESTAMP
);

CREATE INDEX idx_mo_active ON manual_overrides(tenant_id, active)
    WHERE active = TRUE;
CREATE INDEX idx_mo_property ON manual_overrides(property_id, active)
    WHERE active = TRUE;
