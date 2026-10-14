-- V37: одноразовые токены входа по ссылке — сброс пароля (RESET) и подтверждение почты (VERIFY).
-- В базе только SHA-256 токена: утёкший дамп не даёт войти по ссылке.
-- Время — TIMESTAMPTZ: срок сравнивается с now() и не зависит от часового пояса сессии.

CREATE TABLE auth_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind        VARCHAR(20) NOT NULL,
    token_hash  CHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_auth_tokens_user_kind ON auth_tokens(user_id, kind);

-- Прежние колонки токенов (V12) хранили токен открытым текстом и не использовались.
-- Колонки остаются, значения стираются.
UPDATE users
SET password_reset_token = NULL,
    password_reset_expires_at = NULL,
    email_verification_token = NULL,
    email_verification_expires_at = NULL
WHERE password_reset_token IS NOT NULL
   OR email_verification_token IS NOT NULL;
