-- Раздел «Пользователи» в админке (/admin/users).
--
-- last_login_at — время последнего успешного входа (форма входа и автоматический вход после
-- регистрации). Покрыто согласием пользователя: «дата и время входа и действий в кабинете,
-- связанные с безопасностью аккаунта».
--
-- signup_* — откуда пришёл зарегистрировавшийся: метки utm_source / utm_medium / utm_campaign
-- из адреса страницы регистрации и домен сайта, с которого был переход (только домен, без пути и
-- параметров). Пишутся один раз при регистрации. Покрытие документами — вопрос юристу, см.
-- patches/INTEGRATION_ADMIN.md.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS last_login_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS signup_utm_source      VARCHAR(100),
    ADD COLUMN IF NOT EXISTS signup_utm_medium      VARCHAR(100),
    ADD COLUMN IF NOT EXISTS signup_utm_campaign    VARCHAR(100),
    ADD COLUMN IF NOT EXISTS signup_referrer_domain VARCHAR(255);

-- Сортировка списка по последнему входу и дате регистрации
CREATE INDEX IF NOT EXISTS idx_users_last_login ON users (last_login_at);
CREATE INDEX IF NOT EXISTS idx_users_created ON users (created_at);
