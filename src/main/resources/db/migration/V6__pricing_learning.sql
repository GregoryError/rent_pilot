-- Pricing learning system: raw decisions log + insights (learned patterns)

CREATE TABLE pricing_decisions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    property_id BIGINT NOT NULL,
    target_date DATE NOT NULL,
    decided_at TIMESTAMP NOT NULL DEFAULT NOW(),
    price INT NOT NULL,
    min_stay INT NOT NULL,
    ai_multiplier DOUBLE PRECISION,
    days_ahead INT NOT NULL,
    window_len INT,
    is_weekend BOOLEAN NOT NULL DEFAULT FALSE,
    is_holiday BOOLEAN NOT NULL DEFAULT FALSE,
    booking_pace DOUBLE PRECISION,
    competitor_avg_price INT,
    booked_at TIMESTAMP,
    booked_amount NUMERIC(10,2),
    days_to_booking INT,
    outcome VARCHAR(20)
);

CREATE INDEX idx_pd_tenant_target ON pricing_decisions(tenant_id, property_id, target_date);
CREATE INDEX idx_pd_outcome ON pricing_decisions(outcome) WHERE outcome IS NULL;

CREATE TABLE pricing_insights (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    property_id BIGINT,
    pattern_key VARCHAR(80) NOT NULL,
    condition_json JSONB,
    action_json JSONB,
    sample_size INT NOT NULL DEFAULT 0,
    conversion_rate DOUBLE PRECISION,
    avg_days_to_booking DOUBLE PRECISION,
    confidence DOUBLE PRECISION,
    summary_text TEXT,
    discovered_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_confirmed_at TIMESTAMP
);

CREATE INDEX idx_pi_tenant_key ON pricing_insights(tenant_id, pattern_key);

INSERT INTO system_settings (tenant_id, key, value, description) VALUES
    (1, 'learning_enabled', 'true', 'Собирать данные и анализировать паттерны ценообразования'),
    (1, 'learning_analysis_frequency', 'weekly', 'Периодичность анализа: weekly / biweekly / monthly'),
    (1, 'keep_raw_decisions', 'false', 'Сохранять сырые decisions после анализа')
ON CONFLICT DO NOTHING;
