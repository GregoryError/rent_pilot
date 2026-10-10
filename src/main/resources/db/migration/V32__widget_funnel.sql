-- V32: аналитика воронки виджета бронирования (фаза 6).
--
-- Хранятся только агрегаты: сколько раз за день в виджете произошёл шаг воронки, в
-- разбивке по источнику перехода. Ни IP, ни идентификаторов посетителей, ни cookie —
-- персональных данных здесь нет.
--
-- Шаги: view — виджет открыт; dates — выбраны даты; form — начато заполнение формы;
-- submit — форма отправлена; success — заявка или бронь создана.
-- Источник: utm_source, иначе сайт, с которого пришёл посетитель, иначе 'direct'.

CREATE TABLE IF NOT EXISTS widget_funnel_daily (
    widget_id BIGINT       NOT NULL REFERENCES booking_widgets(id),
    tenant_id BIGINT       NOT NULL REFERENCES tenants(id),
    day       DATE         NOT NULL,
    step      VARCHAR(10)  NOT NULL,
    source    VARCHAR(100) NOT NULL,
    hits      INTEGER      NOT NULL DEFAULT 0,
    PRIMARY KEY (widget_id, day, step, source)
);

CREATE INDEX IF NOT EXISTS idx_widget_funnel_tenant ON widget_funnel_daily(tenant_id, day);

-- Счётчик Яндекс.Метрики хозяина: виджет шлёт в него цели на каждом шаге воронки.
ALTER TABLE booking_widgets
    ADD COLUMN IF NOT EXISTS metrika_id VARCHAR(20);
