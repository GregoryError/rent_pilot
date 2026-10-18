-- V45: инструкции по площадкам и статьи в базе (patches/INTEGRATION_ADMIN.md).
--
-- Одна таблица на оба вида: PLATFORM — инструкция по площадке (структурные поля для мастера
-- онбординга в platform JSONB + свободный текст статьи), ARTICLE — только статья. Публичный
-- адрес у обоих — /instrukcii/<slug>, slug уникален. Раньше площадки жили в
-- onboarding/platforms/*.yml — миграция переносит их как есть, файлы удалены.
--
-- status: DRAFT (404 для всех, кроме администратора) / PUBLISHED. hidden_from_search —
-- опубликовано и открывается, но noindex и нет в sitemap.xml. platform.stepsVerified — шаги
-- «где взять ссылку» и «куда вставить» проверены: без этого мастер и страница показывают
-- общий текст.
CREATE TABLE guides (
    id                 BIGSERIAL    PRIMARY KEY,
    kind               VARCHAR(20)  NOT NULL CHECK (kind IN ('PLATFORM', 'ARTICLE')),
    slug               VARCHAR(80)  NOT NULL UNIQUE CHECK (slug ~ '^[a-z][a-z0-9-]*$'),
    title              VARCHAR(200) NOT NULL,
    description        VARCHAR(300) NOT NULL DEFAULT '',
    body_md            TEXT         NOT NULL DEFAULT '',
    cover_image_id     BIGINT,
    status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED')),
    hidden_from_search BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order         INTEGER      NOT NULL DEFAULT 100,
    platform           JSONB,
    published_at       TIMESTAMP,
    created_at         TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT now(),
    CHECK ((kind = 'PLATFORM') = (platform IS NOT NULL))
);

-- Шесть площадок: опубликованы, скрыты из поиска (как прежний draft: true). Шаги Циан, DomClick и
-- Суточно отмечены проверенными (проверены на пилоте), но списки шагов в файлах были пустыми —
-- мастер покажет общий текст, пока шаги не впишут в редакторе.
INSERT INTO guides (kind, slug, title, description, status, hidden_from_search, sort_order, platform, published_at) VALUES
('PLATFORM', 'avito', 'Авито: синхронизация календаря с OptiRent по iCal',
 'Как подключить Авито к календарю OptiRent по iCal: ссылка экспорта, ссылка импорта, частота обновления и особенности площадки.',
 'PUBLISHED', TRUE, 1, '{"name": "Авито", "cabinet": "Авито", "letter": "А", "color": "#0E9558", "logo": null, "domains": ["avito.ru"], "domainsDraft": true, "stepsVerified": false, "fromPlatform": {"steps": [], "screenshot": null}, "toPlatform": {"steps": [], "screenshot": null}, "refresh": "10–60 минут", "notes": []}'::jsonb, TIMESTAMP '2026-10-12 00:00'),
('PLATFORM', 'cian', 'Циан: синхронизация календаря с OptiRent по iCal',
 'Как подключить Циан к календарю OptiRent по iCal: ссылка экспорта, ссылка импорта, частота обновления и особенности площадки.',
 'PUBLISHED', TRUE, 2, '{"name": "Циан", "cabinet": "Циан", "letter": "Ц", "color": "#1D4ED8", "logo": null, "domains": ["cian.ru"], "domainsDraft": false, "stepsVerified": true, "fromPlatform": {"steps": [], "screenshot": null}, "toPlatform": {"steps": [], "screenshot": null}, "refresh": "обычно до получаса", "notes": ["Если вы закрыли дни в OptiRent вручную, а потом удалили запись, Циан может оставить эти дни закрытыми у себя. Проверьте календарь в кабинете Циан и откройте даты там.", "Циан может вернуть вашу же запись обратно как свою. OptiRent это распознаёт и второй бронью не считает."]}'::jsonb, TIMESTAMP '2026-10-12 00:00'),
('PLATFORM', 'sutochno', 'Суточно: синхронизация календаря с OptiRent по iCal',
 'Как подключить Суточно к календарю OptiRent по iCal: ссылка экспорта, ссылка импорта, частота обновления и особенности площадки.',
 'PUBLISHED', TRUE, 3, '{"name": "Суточно", "cabinet": "Суточно", "letter": "С", "color": "#C92F9B", "logo": null, "domains": ["sutochno.ru"], "domainsDraft": true, "stepsVerified": true, "fromPlatform": {"steps": [], "screenshot": null}, "toPlatform": {"steps": [], "screenshot": null}, "refresh": "10–60 минут", "notes": ["Если объявление на Суточно уже подключено к другой программе синхронизации, поле для чужой ссылки в кабинете скрыто. Сначала отключите объявление от той программы — в кабинете Суточно или через их поддержку."]}'::jsonb, TIMESTAMP '2026-10-12 00:00'),
('PLATFORM', 'ostrovok', 'Островок: синхронизация календаря с OptiRent по iCal',
 'Как подключить Островок к календарю OptiRent по iCal: ссылка экспорта, ссылка импорта, частота обновления и особенности площадки.',
 'PUBLISHED', TRUE, 4, '{"name": "Островок", "cabinet": "Островка", "letter": "О", "color": "#0B84D8", "logo": null, "domains": ["ostrovok.ru"], "domainsDraft": true, "stepsVerified": false, "fromPlatform": {"steps": [], "screenshot": null}, "toPlatform": {"steps": [], "screenshot": null}, "refresh": "10–60 минут", "notes": []}'::jsonb, TIMESTAMP '2026-10-12 00:00'),
('PLATFORM', 'domclick', 'DomClick: синхронизация календаря с OptiRent по iCal',
 'Как подключить DomClick к календарю OptiRent по iCal: ссылка экспорта, ссылка импорта, частота обновления и особенности площадки.',
 'PUBLISHED', TRUE, 5, '{"name": "DomClick", "cabinet": "DomClick", "letter": "D", "color": "#3B9A2F", "logo": null, "domains": ["domclick.ru"], "domainsDraft": false, "stepsVerified": true, "fromPlatform": {"steps": [], "screenshot": null}, "toPlatform": {"steps": [], "screenshot": null}, "refresh": "обычно до часа", "notes": ["Если вы закрыли дни в OptiRent вручную, а потом удалили запись, DomClick может оставить эти дни закрытыми у себя. Проверьте календарь в кабинете DomClick и откройте даты там.", "DomClick может вернуть вашу же запись обратно как свою. OptiRent это распознаёт и второй бронью не считает."]}'::jsonb, TIMESTAMP '2026-10-12 00:00'),
('PLATFORM', 'yandex-puteshestviya', 'Яндекс Путешествия: синхронизация календаря с OptiRent по iCal',
 'Как подключить Яндекс Путешествия к календарю OptiRent по iCal: ссылка экспорта, ссылка импорта, частота обновления и особенности площадки.',
 'PUBLISHED', TRUE, 6, '{"name": "Яндекс Путешествия", "cabinet": "Яндекс Путешествий", "letter": "Я", "color": "#E5552B", "logo": null, "domains": ["travel.yandex.ru"], "domainsDraft": true, "stepsVerified": false, "fromPlatform": {"steps": [], "screenshot": null}, "toPlatform": {"steps": [], "screenshot": null}, "refresh": "10–60 минут", "notes": []}'::jsonb, TIMESTAMP '2026-10-12 00:00');
