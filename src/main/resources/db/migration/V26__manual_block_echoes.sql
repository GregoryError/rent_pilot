-- V26: косвенное эхо ручных записей через цепочки каналов.
--
-- Ручная запись (channel_id IS NULL) уходит в iCal-фид всех каналов. Площадка или
-- её channel manager импортирует её и возвращает нам в своём фиде — раньше из этого
-- получалась вторая блокировка, уже «от канала», которая переживала удаление
-- исходной записи и возвращалась при каждой синхронизации.
--
-- 1) manual_block_echoes — «событие (channel_id, external_uid) есть эхо ручной
--    записи manual_block_id». Синхронизация по такому событию блокировку не создаёт.
-- 2) calendar_blocks.cancelled_at — ручная запись удаляется мягко: строка живёт ещё
--    30 дней, занятостью не считается, в экспорт идёт со STATUS:CANCELLED и держит
--    свои эхо-связи, чтобы «хвост» у площадки не вернулся к нам блокировкой.
--
-- Данные не трогаем: существующие блокировки-«сироты» от каналов неотличимы от
-- настоящих броней, их убирают вручную (см. patches/INTEGRATION_MANUAL_ECHO.md).

ALTER TABLE calendar_blocks
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_calendar_blocks_cancelled
    ON calendar_blocks(cancelled_at) WHERE cancelled_at IS NOT NULL;

CREATE TABLE manual_block_echoes (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    manual_block_id BIGINT NOT NULL REFERENCES calendar_blocks(id) ON DELETE CASCADE,
    channel_id BIGINT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    external_uid VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_manual_block_echoes_channel_uid
    ON manual_block_echoes(channel_id, external_uid);
CREATE INDEX idx_manual_block_echoes_block ON manual_block_echoes(manual_block_id);
CREATE INDEX idx_manual_block_echoes_tenant ON manual_block_echoes(tenant_id);
