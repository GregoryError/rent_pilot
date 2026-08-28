-- =============================================
-- RentOptima Core Schema
-- Multi-tenant: all business tables have tenant_id
-- =============================================

-- Tenants
CREATE TABLE tenants (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    slug        VARCHAR(100) NOT NULL UNIQUE,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- Users
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    username        VARCHAR(100) NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255),
    role            VARCHAR(50) NOT NULL DEFAULT 'OWNER',
    tg_chat_id      VARCHAR(50),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, username)
);
CREATE INDEX idx_users_username ON users(username);

-- System settings (key-value, per tenant)
CREATE TABLE system_settings (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id),
    key         VARCHAR(100) NOT NULL,
    value       TEXT,
    encrypted   BOOLEAN NOT NULL DEFAULT FALSE,
    description VARCHAR(500),
    UNIQUE (tenant_id, key)
);

-- Properties
CREATE TABLE properties (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    name            VARCHAR(255) NOT NULL,
    address         VARCHAR(500),
    city            VARCHAR(100),
    rc_object_id    VARCHAR(100),
    feedback_code   VARCHAR(50) NOT NULL UNIQUE,
    housekeeper_code VARCHAR(50) NOT NULL UNIQUE,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_properties_tenant ON properties(tenant_id);
CREATE INDEX idx_properties_feedback_code ON properties(feedback_code);
CREATE INDEX idx_properties_housekeeper_code ON properties(housekeeper_code);

-- Bookings
CREATE TABLE bookings (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    property_id     BIGINT NOT NULL REFERENCES properties(id),
    rc_booking_id   VARCHAR(100),
    source          VARCHAR(100),
    status          VARCHAR(50) NOT NULL DEFAULT 'BOOKED',
    guest_name      VARCHAR(255),
    guest_phone     VARCHAR(50),
    guest_count     INTEGER,
    check_in        DATE NOT NULL,
    check_out       DATE NOT NULL,
    nights          INTEGER NOT NULL,
    amount          NUMERIC(12,2) NOT NULL DEFAULT 0,
    commission      NUMERIC(12,2) NOT NULL DEFAULT 0,
    amount_paid     NUMERIC(12,2) NOT NULL DEFAULT 0,
    amount_refunded NUMERIC(12,2) NOT NULL DEFAULT 0,
    notes           TEXT,
    manager_name    VARCHAR(255),
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_bookings_tenant ON bookings(tenant_id);
CREATE INDEX idx_bookings_property ON bookings(property_id);
CREATE INDEX idx_bookings_checkin ON bookings(check_in);
CREATE INDEX idx_bookings_checkout ON bookings(check_out);
CREATE INDEX idx_bookings_rc_id ON bookings(rc_booking_id);
CREATE INDEX idx_bookings_status ON bookings(status);

-- Expense categories
CREATE TABLE expense_categories (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT NOT NULL REFERENCES tenants(id),
    name        VARCHAR(100) NOT NULL,
    icon        VARCHAR(50),
    auto_create BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, name)
);

-- Expenses
CREATE TABLE expenses (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    property_id     BIGINT REFERENCES properties(id),
    category_id     BIGINT NOT NULL REFERENCES expense_categories(id),
    amount          NUMERIC(12,2) NOT NULL,
    date            DATE NOT NULL,
    comment         VARCHAR(500),
    auto_generated  BOOLEAN NOT NULL DEFAULT FALSE,
    booking_id      BIGINT REFERENCES bookings(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_expenses_tenant_date ON expenses(tenant_id, date);

-- Feedback questions
CREATE TABLE feedback_questions (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    property_id     BIGINT REFERENCES properties(id),
    question_text   VARCHAR(500) NOT NULL,
    question_type   VARCHAR(20) NOT NULL DEFAULT 'SCALE',
    sort_order      INTEGER NOT NULL DEFAULT 0,
    active          BOOLEAN NOT NULL DEFAULT TRUE
);

-- Feedback responses (one per guest visit)
CREATE TABLE feedback_responses (
    id              BIGSERIAL PRIMARY KEY,
    property_id     BIGINT NOT NULL REFERENCES properties(id),
    booking_id      BIGINT REFERENCES bookings(id),
    session_id      VARCHAR(100) NOT NULL UNIQUE,
    guest_name      VARCHAR(255),
    guest_phone     VARCHAR(50),
    completed       BOOLEAN NOT NULL DEFAULT FALSE,
    show_to_housekeeper BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_feedback_property ON feedback_responses(property_id);

-- Feedback answers (one per question per response)
CREATE TABLE feedback_answers (
    id              BIGSERIAL PRIMARY KEY,
    response_id     BIGINT NOT NULL REFERENCES feedback_responses(id),
    question_id     BIGINT NOT NULL REFERENCES feedback_questions(id),
    numeric_value   INTEGER,
    text_value      TEXT,
    answered_at     TIMESTAMP NOT NULL DEFAULT now()
);

-- Pricing rules
CREATE TABLE pricing_rules (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    property_id     BIGINT REFERENCES properties(id),
    rule_key        VARCHAR(100) NOT NULL,
    rule_value      VARCHAR(500) NOT NULL,
    description     VARCHAR(500),
    UNIQUE (tenant_id, property_id, rule_key)
);

-- Price history (what was actually set in RC)
CREATE TABLE price_history (
    id              BIGSERIAL PRIMARY KEY,
    property_id     BIGINT NOT NULL REFERENCES properties(id),
    date            DATE NOT NULL,
    price           NUMERIC(10,2) NOT NULL,
    min_stay        INTEGER,
    source          VARCHAR(50) NOT NULL DEFAULT 'MANUAL',
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_price_history_prop_date ON price_history(property_id, date);

-- Competitor listings
CREATE TABLE competitor_listings (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    property_id     BIGINT REFERENCES properties(id),
    competitor_name VARCHAR(255) NOT NULL,
    url             VARCHAR(1000) NOT NULL,
    platform        VARCHAR(100),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- Competitor price snapshots
CREATE TABLE competitor_prices (
    id              BIGSERIAL PRIMARY KEY,
    listing_id      BIGINT NOT NULL REFERENCES competitor_listings(id),
    date            DATE,
    price           NUMERIC(10,2),
    min_stay        INTEGER,
    scraped_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_comp_prices_listing ON competitor_prices(listing_id, scraped_at);

-- External data sources (TG channels, VK groups, websites)
CREATE TABLE external_sources (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    source_type     VARCHAR(50) NOT NULL,
    url             VARCHAR(1000) NOT NULL,
    name            VARCHAR(255),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    scrape_frequency_hours INTEGER NOT NULL DEFAULT 12,
    last_scraped_at TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- City events
CREATE TABLE city_events (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    title           VARCHAR(500) NOT NULL,
    date_start      DATE NOT NULL,
    date_end        DATE,
    scale           VARCHAR(50) DEFAULT 'LOCAL',
    impact_score    NUMERIC(3,2) DEFAULT 1.0,
    source_id       BIGINT REFERENCES external_sources(id),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_events_dates ON city_events(date_start, date_end);

-- Production calendar (Russian)
CREATE TABLE production_calendar (
    date            DATE PRIMARY KEY,
    day_type        VARCHAR(20) NOT NULL,
    description     VARCHAR(255)
);

-- RC automation log
CREATE TABLE rc_action_log (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    property_id     BIGINT REFERENCES properties(id),
    action_type     VARCHAR(50) NOT NULL,
    payload         JSONB,
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    autopilot_mode  VARCHAR(20),
    requires_approval BOOLEAN NOT NULL DEFAULT FALSE,
    approved_at     TIMESTAMP,
    executed_at     TIMESTAMP,
    result          TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_rc_actions_tenant ON rc_action_log(tenant_id, created_at);

-- AI chat messages
CREATE TABLE chat_messages (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    conversation_id VARCHAR(100) NOT NULL,
    role            VARCHAR(20) NOT NULL,
    content         TEXT NOT NULL,
    tokens          INTEGER,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_tenant_conv ON chat_messages(tenant_id, conversation_id);

-- AI usage log
CREATE TABLE ai_usage_log (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    purpose         VARCHAR(100) NOT NULL,
    model           VARCHAR(50),
    tokens_in       INTEGER NOT NULL DEFAULT 0,
    tokens_out      INTEGER NOT NULL DEFAULT 0,
    cost_usd        NUMERIC(10,6),
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

-- Notification settings
CREATE TABLE notification_settings (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       BIGINT NOT NULL REFERENCES tenants(id),
    event_type      VARCHAR(100) NOT NULL,
    channel         VARCHAR(50) NOT NULL DEFAULT 'TELEGRAM',
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (tenant_id, event_type, channel)
);

-- =============================================
-- Seed data
-- =============================================
INSERT INTO tenants (name, slug) VALUES ('Default', 'default');

INSERT INTO users (tenant_id, username, password_hash, display_name, role)
VALUES (1, 'admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 'Администратор', 'ADMIN');
-- Default password: admin (BCrypt hash) — CHANGE ON FIRST LOGIN

INSERT INTO expense_categories (tenant_id, name, icon, auto_create, sort_order) VALUES
(1, 'Уборка', 'broom', TRUE, 1),
(1, 'Коммунальные услуги', 'zap', FALSE, 2),
(1, 'Расходники', 'package', FALSE, 3),
(1, 'Ремонт', 'wrench', FALSE, 4),
(1, 'Комиссии площадок', 'percent', FALSE, 5),
(1, 'Интернет', 'wifi', FALSE, 6),
(1, 'Прочее', 'more-horizontal', FALSE, 99);

INSERT INTO system_settings (tenant_id, key, value, description) VALUES
(1, 'city', 'Выборг', 'Город объекта'),
(1, 'timezone', 'Europe/Moscow', 'Часовой пояс'),
(1, 'platform_markup_pct', '18.0', 'Средняя наценка площадок, %'),
(1, 'cleaning_cost', '1400', 'Стоимость одной уборки, ₽'),
(1, 'open_ahead_days', '30', 'На сколько дней вперёд открывать даты'),
(1, 'max_min_stay', '10', 'Мин. срок для закрытия дат (soft lock)'),
(1, 'weekday_base_price', '3200', 'Базовая цена будни, ₽'),
(1, 'weekend_base_price', '4200', 'Базовая цена выходные (пт-сб), ₽'),
(1, 'min_price_floor', '2500', 'Минимальная допустимая цена, ₽'),
(1, 'max_price_ceiling', '6000', 'Максимальная допустимая цена, ₽'),
(1, 'auto_price_delta', '50', 'Макс. автоматическое изменение цены, ₽'),
(1, 'visibility_rotation_min', '10', 'Ротация видимости: мин. изменение, ₽'),
(1, 'visibility_rotation_max', '50', 'Ротация видимости: макс. изменение, ₽'),
(1, 'autopilot_mode', 'SOFT', 'Режим автопилота: OFF / SOFT / FULL'),
(1, 'competitor_scrape_hours', '12', 'Частота парсинга конкурентов, часы'),
(1, 'anthropic_api_key', '', 'Ключ Anthropic API'),
(1, 'tg_bot_token', '', 'Токен Telegram-бота');
