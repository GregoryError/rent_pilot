-- V29: виджет бронирования v2, серверная часть (фаза 1).
--
-- 1) booking_widgets — публичный адрес (slug), режим мгновенной брони, срок резерва
--    в минутах, сбор за уборку, скидки за длительность, предоплата, запреты заезда и
--    выезда по дням недели, разрешённые домены для встраивания, контакты хозяина,
--    раскладка (config_json — заполняется в следующих фазах).
--    hold_hours остаётся в таблице, но больше не читается: срок хранится в hold_minutes.
-- 2) promo_codes — промокоды хозяина.
-- 3) bookings — состав гостей, email, номер брони для гостя, разбивка суммы, UTM и
--    referrer, момент согласия на обработку данных, отметка о напоминании хозяину.
--
-- Заявка с виджета теперь держит свою блокировку («якорь») всё время жизни брони:
-- HOLD, пока хозяин не ответил, WIDGET_BOOKING после подтверждения. К якорю, как к
-- ручной записи, привязываются эхо-связи и тени (V26, V27). Отклонённая или истёкшая
-- заявка удаляется мягко (cancelled_at) и уходит в экспорт со STATUS:CANCELLED.
-- Значение block_type = 'WIDGET_BOOKING' новое, ограничения CHECK на колонке нет.

ALTER TABLE booking_widgets
    ADD COLUMN IF NOT EXISTS slug                     VARCHAR(80),
    ADD COLUMN IF NOT EXISTS hold_minutes             INTEGER       NOT NULL DEFAULT 1440,
    ADD COLUMN IF NOT EXISTS cleaning_fee             NUMERIC(10,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS weekly_discount_percent  INTEGER       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS monthly_discount_percent INTEGER       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS prepayment_percent       INTEGER       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pets_allowed             BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS no_checkin_days          VARCHAR(20)   NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS no_checkout_days         VARCHAR(20)   NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS allowed_origins          TEXT          NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS contact_phone            VARCHAR(40),
    ADD COLUMN IF NOT EXISTS contact_telegram         VARCHAR(80),
    ADD COLUMN IF NOT EXISTS contact_whatsapp         VARCHAR(40),
    ADD COLUMN IF NOT EXISTS config_json              JSONB;

UPDATE booking_widgets SET hold_minutes = hold_hours * 60;
UPDATE booking_widgets SET slug = 'w' || id WHERE slug IS NULL;

ALTER TABLE booking_widgets ALTER COLUMN slug SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_booking_widgets_slug ON booking_widgets(slug);

CREATE TABLE IF NOT EXISTS promo_codes (
    id             BIGSERIAL     PRIMARY KEY,
    tenant_id      BIGINT        NOT NULL REFERENCES tenants(id),
    widget_id      BIGINT        NOT NULL REFERENCES booking_widgets(id),
    code           VARCHAR(40)   NOT NULL,
    discount_type  VARCHAR(10)   NOT NULL,           -- PERCENT | AMOUNT
    discount_value NUMERIC(10,2) NOT NULL,
    valid_from     DATE,
    valid_until    DATE,                             -- включительно
    max_uses       INTEGER,                          -- NULL — без ограничения
    used_count     INTEGER       NOT NULL DEFAULT 0,
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- Код хранится в верхнем регистре; уникален в пределах виджета.
CREATE UNIQUE INDEX IF NOT EXISTS idx_promo_codes_widget_code ON promo_codes(widget_id, code);
CREATE INDEX IF NOT EXISTS idx_promo_codes_tenant ON promo_codes(tenant_id);

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS adults                INTEGER,
    ADD COLUMN IF NOT EXISTS children              INTEGER,
    ADD COLUMN IF NOT EXISTS pets                  INTEGER,
    ADD COLUMN IF NOT EXISTS guest_email           VARCHAR(255),
    ADD COLUMN IF NOT EXISTS locale                VARCHAR(5),
    ADD COLUMN IF NOT EXISTS public_code           VARCHAR(12),
    ADD COLUMN IF NOT EXISTS cleaning_fee          NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS discount_amount       NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS promo_code_id         BIGINT REFERENCES promo_codes(id),
    ADD COLUMN IF NOT EXISTS prepayment_amount     NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS utm_source            VARCHAR(100),
    ADD COLUMN IF NOT EXISTS utm_medium            VARCHAR(100),
    ADD COLUMN IF NOT EXISTS utm_campaign          VARCHAR(100),
    ADD COLUMN IF NOT EXISTS referrer              VARCHAR(500),
    ADD COLUMN IF NOT EXISTS consent_at            TIMESTAMP,
    ADD COLUMN IF NOT EXISTS hold_reminder_sent_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS idx_bookings_public_code
    ON bookings(public_code) WHERE public_code IS NOT NULL;
