-- Онбординг: мастер первой настройки, тур, подсказки и чек-лист первых шагов.
-- Состояние хранится на сервере, по одному ряду на пользователя: подсказки не повторяются
-- на другом устройстве, мастер продолжается с того же шага.
--
-- Ряд создаётся при регистрации. У пользователей, созданных раньше, ряда нет — они
-- онбординг не получают, пока сами не нажмут «Знакомство».
CREATE TABLE onboarding_state (
    user_id       BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    tenant_id     BIGINT NOT NULL REFERENCES tenants(id),
    -- A — площадки, B — прямые брони, AB — и то и другое; NULL — ещё не выбрано
    branch        VARCHAR(2),
    -- текущий шаг мастера: start, property, platforms, links, page, done
    wizard_step   VARCHAR(20) NOT NULL DEFAULT 'start',
    -- ACTIVE — идёт, DONE — пройден, SKIPPED — «Пропустить, настрою сам»
    wizard_status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    -- всё остальное: выбранные площадки, созданные объект и страница, кадры тура,
    -- показанные подсказки, пункты чек-листа, «больше не показывать»
    data          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    wizard_finished_at TIMESTAMP
);

CREATE INDEX idx_onboarding_state_tenant ON onboarding_state(tenant_id);
