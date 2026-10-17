-- V16: Channel-abstraction foundation
-- unit_types: категории номеров внутри property (для мини-отелей и в целом более гибкой модели)
-- channels: подключённые каналы к unit_type (RC, Avito, iCal, Manual)
-- calendar_blocks: блокировки дат (ручные брони, ремонт, личное использование)
-- bookings.channel_id: связь брони с каналом откуда пришла

CREATE TABLE unit_types (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    property_id BIGINT NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    unit_count INTEGER NOT NULL DEFAULT 1,
    base_price NUMERIC(10,2),
    weekend_price NUMERIC(10,2),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_unit_types_tenant ON unit_types(tenant_id);
CREATE INDEX idx_unit_types_property ON unit_types(property_id);

-- Дефолтный unit_type для каждой существующей property
INSERT INTO unit_types (tenant_id, property_id, name, unit_count)
SELECT tenant_id, id, 'Основной', 1 FROM properties;


CREATE TABLE channels (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    unit_type_id BIGINT NOT NULL REFERENCES unit_types(id) ON DELETE CASCADE,
    channel_type VARCHAR(20) NOT NULL, -- RC, AVITO, ICAL, MANUAL
    name VARCHAR(200) NOT NULL,
    -- Конфиг канала: для RC — rc_object_id, для Avito — item_id + oauth,
    -- для iCal — import_url и/или export_secret
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    sync_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_sync_at TIMESTAMP,
    last_error TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_channels_tenant ON channels(tenant_id);
CREATE INDEX idx_channels_unit_type ON channels(unit_type_id);
CREATE INDEX idx_channels_type ON channels(channel_type);

-- Секрет для экспорта iCal (уникальная случайная строка для каждой iCal-подключки)
-- Это поле нужно только для channel_type='ICAL' в export-режиме;
-- храним прямо в config_json.


-- Ручные брони и блокировки (ремонт, личное использование)
CREATE TABLE calendar_blocks (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    unit_type_id BIGINT NOT NULL REFERENCES unit_types(id) ON DELETE CASCADE,
    block_type VARCHAR(20) NOT NULL, -- MANUAL_BOOKING, MAINTENANCE, OWNER_USE, HOLD
    from_date DATE NOT NULL,
    to_date DATE NOT NULL, -- checkout date, не включительно
    reason VARCHAR(255),
    note TEXT,
    created_by_user_id BIGINT REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_calendar_blocks_tenant ON calendar_blocks(tenant_id);
CREATE INDEX idx_calendar_blocks_unit_type ON calendar_blocks(unit_type_id);
CREATE INDEX idx_calendar_blocks_dates ON calendar_blocks(from_date, to_date);


-- Bookings: канал откуда пришла бронь
ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS channel_id BIGINT REFERENCES channels(id),
    ADD COLUMN IF NOT EXISTS unit_type_id BIGINT REFERENCES unit_types(id),
    -- external_id — идентификатор брони в исходной системе (RC booking_id, Avito booking_id, UID из iCal)
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(200);

CREATE INDEX IF NOT EXISTS idx_bookings_channel ON bookings(channel_id);
CREATE INDEX IF NOT EXISTS idx_bookings_unit_type ON bookings(unit_type_id);
CREATE INDEX IF NOT EXISTS idx_bookings_external_id ON bookings(external_id);

-- Заполняем unit_type_id у существующих bookings — присваиваем дефолтный unit_type property
UPDATE bookings b
SET unit_type_id = (
    SELECT id FROM unit_types ut
    WHERE ut.property_id = b.property_id
    ORDER BY ut.id LIMIT 1
)
WHERE unit_type_id IS NULL;
