-- Глобальные настройки сервиса (не тенанта): выключатель «Сервис открыт», страница /admin/service.
-- system_settings — по тенантам, поэтому отдельная таблица «ключ — значение».
--
--   registration_open — регистрация открыта (true/false)
--   login_open        — вход для пользователей открыт; администратор входит всегда
--   notice_title      — заголовок объявления, когда закрыто
--   notice_text       — текст объявления: абзацы и ссылки (Markdown, рендер — LegalMarkdown)
--
-- Аварийный выход: APP_SERVICE_LOCK_OVERRIDE=open открывает вход и регистрацию, не глядя сюда.
CREATE TABLE IF NOT EXISTS service_settings (
    key                VARCHAR(50) PRIMARY KEY,
    value              TEXT        NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_user_id BIGINT      REFERENCES users(id) ON DELETE SET NULL
);

-- По умолчанию открыто: выкладка ничего не закрывает
INSERT INTO service_settings(key, value) VALUES
    ('registration_open', 'true'),
    ('login_open', 'true'),
    ('notice_title', 'Сервис на стадии запуска'),
    ('notice_text', 'Регистрация откроется в ближайшее время.')
ON CONFLICT (key) DO NOTHING;
