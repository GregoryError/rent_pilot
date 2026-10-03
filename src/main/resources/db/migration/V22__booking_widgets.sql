-- V22: виджет / страница бронирования (блок 4.9).
--
-- 1. booking_widgets — настройки страницы бронирования хоста. Один виджет
--    обслуживает три способа показа: /book/{secret} (ссылка для мессенджеров),
--    /widget/{secret} (iframe) и /widget.js (вставка скриптом).
--    Виджет привязан к каналу типа WIDGET: его заявки и брони идут через общую
--    Channel-абстракцию и сами попадают в шахматку и в iCal-экспорт других каналов.
--    unit_type_id — один, а не массив: канал тоже привязан к одной категории,
--    выбор из нескольких категорий в одной заявке в MVP не входит.
--
-- 2. calendar_blocks.expires_at — срок жизни HOLD по заявке с виджета. Истёкший
--    hold удаляет WidgetBookingService, даты снова свободны.
--
-- Заявка = CalendarBlock HOLD (держит даты) + Booking со status='PENDING'
-- (data_source='WIDGET', external_id = UUID заявки, он же external_uid блока).

CREATE TABLE IF NOT EXISTS booking_widgets (
    id                  BIGSERIAL    PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES tenants(id),
    channel_id          BIGINT       NOT NULL REFERENCES channels(id),
    unit_type_id        BIGINT       NOT NULL REFERENCES unit_types(id),
    secret              VARCHAR(64)  NOT NULL,
    title               VARCHAR(255) NOT NULL,
    min_nights          INTEGER      NOT NULL DEFAULT 1,
    max_nights          INTEGER      NOT NULL DEFAULT 30,
    max_guests          INTEGER      NOT NULL DEFAULT 4,
    booking_window_days INTEGER      NOT NULL DEFAULT 180,
    checkin_time        TIME         NOT NULL DEFAULT '14:00',
    checkout_time       TIME         NOT NULL DEFAULT '12:00',
    mode                VARCHAR(20)  NOT NULL DEFAULT 'REQUEST',
    hold_hours          INTEGER      NOT NULL DEFAULT 24,
    show_price          BOOLEAN      NOT NULL DEFAULT TRUE,
    show_powered_by     BOOLEAN      NOT NULL DEFAULT TRUE,
    custom_css          TEXT,
    theme               VARCHAR(20)  NOT NULL DEFAULT 'light',

    -- Поля для страницы /book/{secret}; все необязательные
    description         TEXT,
    rules               TEXT,
    cancellation_policy TEXT,
    address_hint        VARCHAR(255),
    photos_json         JSONB,
    show_host_contact   BOOLEAN      NOT NULL DEFAULT FALSE,

    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_booking_widgets_secret ON booking_widgets(secret);
CREATE INDEX IF NOT EXISTS idx_booking_widgets_tenant ON booking_widgets(tenant_id);

ALTER TABLE calendar_blocks
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_calendar_blocks_expires
    ON calendar_blocks(expires_at) WHERE expires_at IS NOT NULL;
