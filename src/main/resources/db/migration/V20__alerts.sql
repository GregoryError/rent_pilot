-- V20: алерты (блок 4.7) — детектор конфликтов и падение синхронизации канала.
--
-- 1. channels.consecutive_errors — сколько синхронизаций подряд закончились ошибкой.
--    last_error хранит только текст последней; чтобы не слать алерт на разовый
--    таймаут площадки, нужен счётчик. Сбрасывается первой успешной синхронизацией.
--
-- 2. alert_events — журнал алертов. Одна строка = одна проблема от появления
--    до исчезновения:
--    - 'CONFLICT'     — ручная запись пересекается с занятостью с площадки;
--    - 'CHANNEL_DOWN' — канал не синхронизируется N раз подряд.
--    Нужен, чтобы (а) не слать одно и то же в Telegram каждый прогон детектора,
--    (б) видеть историю пересечений на пилоте (блок 4.8).
--    notified_at IS NULL — сообщение ещё не доставлено (Telegram не настроен или
--    недоступен), детектор повторит отправку на следующем прогоне.

ALTER TABLE channels
    ADD COLUMN IF NOT EXISTS consecutive_errors INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS alert_events (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenants(id),
    alert_type    VARCHAR(30)  NOT NULL,
    dedup_key     VARCHAR(200) NOT NULL,
    unit_type_id  BIGINT       REFERENCES unit_types(id) ON DELETE CASCADE,
    channel_id    BIGINT       REFERENCES channels(id) ON DELETE CASCADE,
    from_date     DATE,
    to_date       DATE,
    message       TEXT         NOT NULL,
    first_seen_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    last_seen_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    notified_at   TIMESTAMP,
    resolved_at   TIMESTAMP
);

-- Открытый алерт по одной и той же проблеме — только один.
CREATE UNIQUE INDEX IF NOT EXISTS idx_alert_events_open
    ON alert_events(tenant_id, alert_type, dedup_key)
    WHERE resolved_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_alert_events_tenant_seen
    ON alert_events(tenant_id, first_seen_at DESC);
