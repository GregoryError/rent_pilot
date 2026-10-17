-- V38: мягкое подтверждение почты.
--
-- email_verified_at          — когда адрес подтверждён по ссылке (null — не подтверждён ссылкой;
--                              у пользователей до V38 флаг email_verified стоял без проверки)
-- verify_deadline            — до какого момента кабинет работает без подтверждения; после —
--                              экран «Подтвердите почту, чтобы продолжить»
-- page_needs_verified_email  — страница бронирования не принимает гостей, пока адрес не
--                              подтверждён. Только у аккаунтов, созданных после выкладки: уже
--                              розданные гостям ссылки не гаснут.

ALTER TABLE users
    ADD COLUMN email_verified_at TIMESTAMPTZ,
    ADD COLUMN verify_deadline TIMESTAMPTZ,
    ADD COLUMN page_needs_verified_email BOOLEAN NOT NULL DEFAULT FALSE;

-- Существующие хозяева почту не подтверждали: плашка и экран через 7 дней от выкладки.
-- Администратор и пользователи без адреса не затрагиваются.
UPDATE users
SET email_verified = FALSE,
    verify_deadline = now() + interval '7 days'
WHERE role = 'OWNER'
  AND username <> 'admin'
  AND email IS NOT NULL;
