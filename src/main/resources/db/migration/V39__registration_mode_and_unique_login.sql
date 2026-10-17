-- V39: логин и адрес почты уникальны без учёта регистра; режим подтверждения почты при регистрации.
--
-- Вход ищет пользователя по логину без тенанта, а уникальность была только по паре
-- (tenant_id, username) и по адресу с учётом регистра. Если в базе уже есть совпадения без
-- учёта регистра, миграция останавливается с их списком — ничего не переименовывает.
-- Как найти и развести совпадения — patches/INTEGRATION_AUTH.md, раздел 4.

DO $$
DECLARE
    dup TEXT;
BEGIN
    SELECT string_agg(k, ', ') INTO dup FROM (
        SELECT lower(username) AS k FROM users GROUP BY lower(username) HAVING count(*) > 1
    ) d;
    IF dup IS NOT NULL THEN
        RAISE EXCEPTION 'V39: логины совпадают без учёта регистра: %. Разведите их вручную (patches/INTEGRATION_AUTH.md, раздел 4) и перезапустите приложение.', dup;
    END IF;

    SELECT string_agg(k, ', ') INTO dup FROM (
        SELECT lower(email) AS k FROM users WHERE email IS NOT NULL GROUP BY lower(email) HAVING count(*) > 1
    ) d;
    IF dup IS NOT NULL THEN
        RAISE EXCEPTION 'V39: адреса почты совпадают без учёта регистра: %. Разведите их вручную (patches/INTEGRATION_AUTH.md, раздел 4) и перезапустите приложение.', dup;
    END IF;
END $$;

CREATE UNIQUE INDEX ux_users_username_ci ON users (lower(username));
CREATE UNIQUE INDEX ux_users_email_ci ON users (lower(email)) WHERE email IS NOT NULL;

-- Режим подтверждения почты — настройка всего сервиса, хранится у тенанта администратора.
-- SOFT — кабинет сразу, подтверждение в течение 7 дней; STRICT — кабинет только после
-- подтверждения; AUTO — SOFT, пока «настоящих» пользователей меньше порога, потом STRICT.
INSERT INTO system_settings (tenant_id, key, value, description)
SELECT tenant_id, 'registration_verification', 'AUTO', 'Подтверждение почты при регистрации: SOFT / STRICT / AUTO'
FROM users WHERE role = 'ADMIN' ORDER BY id LIMIT 1
ON CONFLICT (tenant_id, key) DO NOTHING;

INSERT INTO system_settings (tenant_id, key, value, description)
SELECT tenant_id, 'registration_auto_threshold', '100', 'AUTO: с какого числа настоящих пользователей включать STRICT'
FROM users WHERE role = 'ADMIN' ORDER BY id LIMIT 1
ON CONFLICT (tenant_id, key) DO NOTHING;
