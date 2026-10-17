-- V19: архитектурная подготовка под переход на API-каналы + таблица запланированных цен.
--
-- 1. bookings.data_source — явный маркер, откуда у брони данные:
--    - 'RC'      — пришла через RealtyCalendar (legacy и новая);
--    - 'ICAL'    — пришла с iCal-канала; подробностей гостя/суммы НЕ будет;
--    - 'AVITO'   — пришла с Avito API; полные данные;
--    - 'MANUAL'  — завёл оператор в UI; данные опциональны.
--    Для дашборда это ключ: по бронированию с подробностями берём реальную сумму,
--    по бронированию без подробностей (ICAL) моделируем по planned_price × ночи.
--    Все существующие строки — исторические RC-брони, поэтому backfill = 'RC'.
--
-- 2. planned_prices — что оператор планирует выставлять на площадках на каждую дату.
--    Используется:
--    а) шахматкой, чтобы показывать цену мелким шрифтом на ячейке
--       (приоритет: planned_price > unit_type.weekend_price > unit_type.base_price);
--    б) дашбордом, чтобы считать прогноз выручки для занятых дней без суммы.
--    Пока оператор ставит цены на площадках сам — наша роль только отобразить и
--    сохранить его план. Когда появится push в Avito, тот же planned_price станет
--    источником для публикации.

ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS data_source VARCHAR(30);

UPDATE bookings
   SET data_source = 'RC'
 WHERE data_source IS NULL;

CREATE INDEX IF NOT EXISTS idx_bookings_data_source ON bookings(data_source);

CREATE TABLE IF NOT EXISTS planned_prices (
    id            BIGSERIAL   PRIMARY KEY,
    tenant_id     BIGINT      NOT NULL REFERENCES tenants(id),
    unit_type_id  BIGINT      NOT NULL REFERENCES unit_types(id) ON DELETE CASCADE,
    date          DATE        NOT NULL,
    price         NUMERIC(10,2) NOT NULL,
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    UNIQUE (unit_type_id, date)
);

CREATE INDEX IF NOT EXISTS idx_planned_prices_tenant ON planned_prices(tenant_id);
CREATE INDEX IF NOT EXISTS idx_planned_prices_date ON planned_prices(date);
