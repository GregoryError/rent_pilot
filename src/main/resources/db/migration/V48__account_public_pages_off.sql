-- Действия администратора с аккаунтом (/admin/users/<id>).
--
-- «Отключить» — tenants.active = FALSE и users.active = FALSE у всех пользователей аккаунта:
-- вход запрещён, открытые сессии завершаются. Страницы бронирования при этом работают.
--
-- «Отключить и публичные страницы» — дополнительно public_pages_off: страницы бронирования
-- /b/<slug> отвечают 410, заявки через виджет не принимаются, прежние адреса /book/, /widget/
-- и короткие /q/ ведут туда же. Включение аккаунта снимает оба признака.
ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS public_pages_off BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS disabled_at      TIMESTAMPTZ;
