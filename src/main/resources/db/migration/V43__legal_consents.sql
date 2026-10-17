-- V43: согласия по правовым документам (patches/INTEGRATION_LEGAL.md).

-- Версии документов, на которые пользователь дал согласие при регистрации или во всплывающем
-- окне. Время — прежние agreed_to_terms_at / agreed_to_consent_at.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS terms_version            INTEGER,
    ADD COLUMN IF NOT EXISTS privacy_version          INTEGER,
    ADD COLUMN IF NOT EXISTS consent_version          INTEGER,
    -- Согласие нужно запросить заново: у всех, кто был до V42, версии пустые
    ADD COLUMN IF NOT EXISTS legal_reconsent_required BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE users SET legal_reconsent_required = TRUE WHERE consent_version IS NULL;

-- Согласие гостя в заявке (время — consent_at) и согласие автора отзыва (время — agreed_to_consent_at)
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS guest_consent_version INTEGER;
ALTER TABLE feedback_responses ADD COLUMN IF NOT EXISTS consent_version INTEGER;

-- Сведения об операторе для гостей: хозяин — оператор данных своих гостей, его имя и контакт
-- стоят в согласии гостя. Одни на аккаунт.
CREATE TABLE guest_operators (
    tenant_id  BIGINT       PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    contact    VARCHAR(255) NOT NULL,
    inn        VARCHAR(12),
    address    VARCHAR(500),
    updated_at TIMESTAMP    NOT NULL DEFAULT now()
);

-- Журнал согласий на распространение (ст. 10.1 152-ФЗ): каждое изменение набора показанных
-- гостям полей — новая строка. Действует последняя строка страницы бронирования.
CREATE TABLE public_data_consents (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT    NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    widget_id        BIGINT    NOT NULL REFERENCES booking_widgets(id) ON DELETE CASCADE,
    user_id          BIGINT    REFERENCES users(id) ON DELETE SET NULL,
    user_email       VARCHAR(255),
    fields           TEXT      NOT NULL,
    document_version INTEGER   NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_public_data_consents_widget ON public_data_consents(widget_id, id DESC);

-- Страницы, созданные до V43, 30 дней принимают заявки с запасным текстом согласия гостя,
-- пока хозяин не заполнит сведения об операторе. Новые страницы (NULL) без них заявок не принимают.
ALTER TABLE booking_widgets
    ADD COLUMN IF NOT EXISTS legal_grace_until    TIMESTAMP,
    ADD COLUMN IF NOT EXISTS legal_notice_sent_at TIMESTAMP;
UPDATE booking_widgets SET legal_grace_until = now() + INTERVAL '30 days';
