-- Онбординг, фаза 4.

-- Уведомления сопровождения после мастера: «площадка впервые забрала нашу ссылку» и
-- «с площадки впервые пришли брони». По одному на площадку и вид — повторно не шлётся.
-- Показывается плашкой при следующем открытии кабинета и уходит в Telegram, если он подключён.
CREATE TABLE onboarding_notices (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT NOT NULL REFERENCES tenants(id),
    channel_id BIGINT NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    -- FETCHED — площадка забрала ссылку, BOOKINGS — пришли первые брони
    kind       VARCHAR(10) NOT NULL,
    text       VARCHAR(300) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    -- когда плашку показали в интерфейсе; NULL — ещё не видели
    shown_at   TIMESTAMP,
    UNIQUE (channel_id, kind)
);

CREATE INDEX idx_onboarding_notices_pending ON onboarding_notices(tenant_id) WHERE shown_at IS NULL;

-- Воронка онбординга: сколько раз за день случилось событие. Только счётчики — без
-- пользователей, тенантов, IP и любых идентификаторов: по этой таблице нельзя узнать, кто
-- именно прошёл или бросил мастер.
CREATE TABLE onboarding_funnel_daily (
    day   DATE        NOT NULL,
    event VARCHAR(40) NOT NULL,
    hits  INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (day, event)
);
