-- V33: печатная карточка для квартиры (виджет v2, фаза 8).
--
-- 1) widget_qr_links — короткие адреса для QR-кодов и сообщений гостю:
--      CARD     /q/<code> → страница бронирования с промокодом, источник qr_card;
--      MESSAGE  /q/<code> → то же, источник guest_message (текст, который хозяин шлёт гостю);
--      FEEDBACK /f/<code> → форма обратной связи.
--    Перевыпуск ссылки не удаляет старую, а помечает её revoked_at: карточки уже
--    напечатаны, старый код должен открывать страницу бронирования (без промокода), а не 404.
-- 2) widget_cards — настройки карточки, одна на виджет. Пароль Wi-Fi хранится
--    зашифрованным (ENCRYPTION_KEY) и наружу не отдаётся.
-- 3) feedback_responses.source — откуда пришёл ответ: NULL — обычная анкета,
--    'CARD' — с карточки в квартире.

CREATE TABLE IF NOT EXISTS widget_qr_links (
    id            BIGSERIAL   PRIMARY KEY,
    tenant_id     BIGINT      NOT NULL REFERENCES tenants(id),
    widget_id     BIGINT      NOT NULL REFERENCES booking_widgets(id),
    code          VARCHAR(12) NOT NULL,
    kind          VARCHAR(10) NOT NULL,              -- CARD | MESSAGE | FEEDBACK
    promo_code_id BIGINT      REFERENCES promo_codes(id),
    revoked_at    TIMESTAMP,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_widget_qr_links_code ON widget_qr_links(code);
CREATE INDEX IF NOT EXISTS idx_widget_qr_links_widget ON widget_qr_links(widget_id, kind);

CREATE TABLE IF NOT EXISTS widget_cards (
    widget_id         BIGINT      PRIMARY KEY REFERENCES booking_widgets(id),
    tenant_id         BIGINT      NOT NULL REFERENCES tenants(id),
    format            VARCHAR(10) NOT NULL DEFAULT 'A6',   -- CARD (90×50 мм) | A6 | A5
    paper             VARCHAR(4)  NOT NULL DEFAULT 'A4',   -- на чём печатать: A4 | A5 | A6
    promo_code_id     BIGINT      REFERENCES promo_codes(id),
    texts_json        JSONB,                               -- тексты, изменённые хозяином
    photo_id          BIGINT      REFERENCES widget_photos(id) ON DELETE SET NULL,
    wifi_ssid         VARCHAR(64),
    wifi_password_enc TEXT,
    show_contacts     BOOLEAN     NOT NULL DEFAULT FALSE,
    show_feedback     BOOLEAN     NOT NULL DEFAULT FALSE,
    lang              VARCHAR(4)  NOT NULL DEFAULT 'ru',   -- ru | en | both
    mono              BOOLEAN     NOT NULL DEFAULT FALSE,  -- для чёрно-белого принтера
    updated_at        TIMESTAMP   NOT NULL DEFAULT NOW()
);

ALTER TABLE feedback_responses
    ADD COLUMN IF NOT EXISTS source VARCHAR(20);
