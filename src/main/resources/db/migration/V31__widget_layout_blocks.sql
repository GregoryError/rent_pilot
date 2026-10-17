-- V31: компоновки виджета бронирования (фаза 4).
--
-- Раскладка и оформление лежат в booking_widgets.config_json (колонка есть с V29):
--   { "preset": "split", "hidden": ["map"], "custom": { ... },
--     "theme": { "accent": "#0f766e", "radius": 14, "font": "system" } }
-- Светлая / тёмная / авто тема — по-прежнему в колонке theme.
-- Здесь — только содержимое двух новых блоков: удобства и ссылка на карту.

ALTER TABLE booking_widgets
    ADD COLUMN IF NOT EXISTS amenities TEXT,          -- по одному удобству в строке
    ADD COLUMN IF NOT EXISTS map_url   VARCHAR(500);  -- ссылка на точку в Яндекс Картах, 2ГИС или Google Maps
