-- V21: журнал обращений к нашему iCal-экспорту (блок 4.8, пилот на Садовой).
--
-- Площадка узнаёт о нашей занятости только когда сама забирает /ical/{secret}.ics.
-- Как часто она это делает — и есть задержка распространения занятости наружу.
-- Записываем каждое обращение: по ним считаем интервал опроса и то, сколько
-- прошло от ручной записи в шахматке до ближайшего запроса фида площадкой.
--
-- Строки старше 30 дней удаляет ChannelDiagnosticsService.

CREATE TABLE IF NOT EXISTS channel_feed_fetches (
    id            BIGSERIAL   PRIMARY KEY,
    tenant_id     BIGINT      NOT NULL REFERENCES tenants(id),
    channel_id    BIGINT      NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    fetched_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    user_agent    VARCHAR(255),
    periods_count INTEGER     NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_channel_feed_fetches_channel_time
    ON channel_feed_fetches(channel_id, fetched_at);
