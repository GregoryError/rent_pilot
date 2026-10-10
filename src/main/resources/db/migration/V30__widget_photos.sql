-- V30: фотографии страницы бронирования (виджет v2, фаза 3).
--
-- Раньше хозяин давал прямые ссылки на чужой хостинг (booking_widgets.photos_json).
-- Теперь фото загружаются к нам: при загрузке оригинал пережимается в несколько ширин
-- (JPEG и WebP), сами файлы лежат на диске в каталоге загрузок (том docker), здесь —
-- только описание. Оригинал не хранится; метаданные снимка (в том числе координаты
-- съёмки) при пережатии отбрасываются.
--
-- photos_json остаётся: ссылки, заданные раньше, показываются после загруженных фото.

CREATE TABLE IF NOT EXISTS widget_photos (
    id         BIGSERIAL    PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenants(id),
    widget_id  BIGINT       NOT NULL REFERENCES booking_widgets(id),
    position   INTEGER      NOT NULL DEFAULT 0,
    file_key   VARCHAR(32)  NOT NULL,           -- имя файлов: <file_key>-<ширина>.<jpg|webp>
    width      INTEGER      NOT NULL,           -- размеры самого крупного варианта
    height     INTEGER      NOT NULL,
    widths     VARCHAR(40)  NOT NULL,           -- какие ширины есть: «480,960,1600»
    has_webp   BOOLEAN      NOT NULL DEFAULT FALSE,
    lqip       TEXT         NOT NULL,           -- размытая заглушка, data URI ~1 КБ
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_widget_photos_key ON widget_photos(file_key);
CREATE INDEX IF NOT EXISTS idx_widget_photos_widget ON widget_photos(widget_id, position);
CREATE INDEX IF NOT EXISTS idx_widget_photos_tenant ON widget_photos(tenant_id);
