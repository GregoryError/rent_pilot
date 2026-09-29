-- V17: idempotency and export secret for channels + calendar_blocks channel linkage

-- channels: секрет для публичного iCal фида + счётчики прогона
ALTER TABLE channels
    ADD COLUMN IF NOT EXISTS export_secret VARCHAR(64),
    ADD COLUMN IF NOT EXISTS last_sync_imported INTEGER,
    ADD COLUMN IF NOT EXISTS last_sync_removed INTEGER;

CREATE UNIQUE INDEX IF NOT EXISTS idx_channels_export_secret
    ON channels(export_secret) WHERE export_secret IS NOT NULL;

-- calendar_blocks: привязка к каналу-источнику и внешний UID (для iCal-импорта)
ALTER TABLE calendar_blocks
    ADD COLUMN IF NOT EXISTS channel_id BIGINT REFERENCES channels(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS external_uid VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_calendar_blocks_channel
    ON calendar_blocks(channel_id);

-- Ключ идемпотентности iCal-импорта: (channel_id, external_uid) уникален
CREATE UNIQUE INDEX IF NOT EXISTS idx_calendar_blocks_channel_uid
    ON calendar_blocks(channel_id, external_uid)
    WHERE channel_id IS NOT NULL AND external_uid IS NOT NULL;

-- Ключ идемпотентности для bookings: (channel_id, external_id) уникален
CREATE UNIQUE INDEX IF NOT EXISTS idx_bookings_channel_external
    ON bookings(channel_id, external_id)
    WHERE channel_id IS NOT NULL AND external_id IS NOT NULL;
