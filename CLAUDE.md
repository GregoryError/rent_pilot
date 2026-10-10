# CLAUDE.md

Проектный контекст для Claude Code. Читается автоматически в начале каждой сессии.

---

## Что за проект

**OptiRent** (в репе — RentOptima) — веб-сервис для оптимизации короткосрочной аренды. Целевая аудитория: хосты 1-2 объектов, ведущие брони "в блокноте". Позиционирование — бесплатный сервис через сообщества.

Ключевая функция: iCal-хаб для синхронизации бронирований между площадками (Avito, Sutochno, Ostrovok, Twil, Booking) + AI-рекомендации цен. Автопилот работает в режиме советника без автоматического пуша.

Раньше проект работал поверх RealtyCalendar. Интеграция полностью убрана из кода (V28): клиент, синхронизация броней, вебхук, пуш цен, карточка в «Интеграциях», поле ID объекта. Работаем только через iCal-каналы. В базе остались колонки `properties.rc_object_id`, `bookings.rc_booking_id` и исторические брони с `data_source = 'RC'` (`Booking.DATA_SOURCE_LEGACY`) — они показываются в шахматке и выручке, но больше не обновляются. Не возвращать упоминания RealtyCalendar в интерфейс.

---

## Стек

- Java 21
- Spring Boot 3.3.2 (Spring MVC + Thymeleaf + Spring Security + Spring Data JPA)
- PostgreSQL 16
- Flyway (миграции V1..V32+)
- Thymeleaf + Layout Dialect
- Lombok
- Hibernate Hypersistence Utils (JSONB поддержка)
- Docker + docker-compose
- Maven (./mvnw)

---

## Как собирать

```bash
# Локально, без docker
./mvnw package -DskipTests

# Через docker (как в проде)
docker compose up --build -d
```

---

## Как запускать

```bash
# docker-compose поднимает БД + приложение
docker compose up -d

# Логи
docker compose logs -f app

# Остановить
docker compose down

# С пересборкой образа после изменений
docker compose up --build -d
```

### Переменные окружения (.env)

```
DB_PASSWORD=...
ENCRYPTION_KEY=<base64, AES-256-GCM>
APP_ADMIN_PASSWORD=...
APP_PORT=8080           # или STAGING_APP_PORT=8081, PARTNER_APP_PORT=8082
```

ENCRYPTION_KEY общий для prod/staging/partner — чтобы можно было переносить зашифрованные данные.

---

## Соглашения

### Property использует JdbcTemplate, не JPA

В Property.java **двойной маппинг** `tenant_id` (JPA ManyToOne + отдельное поле `tenantId`):

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "tenant_id", nullable = false, insertable = false, updatable = false)
private Tenant tenant;

@Column(name = "tenant_id", nullable = false)
private Long tenantId;
```

Это нужно для JdbcTemplate-операций в репозитории. **Не удалять второе поле**, иначе SQL insert отвалится.

### Thymeleaf — pipe-syntax в th:class

Для динамических классов использовать pipe-syntax, не `+`:

```html
<!-- Правильно -->
<a th:class="|nav-item ${activePage == 'dashboard'} ? 'nav-item--active' : ''|">

<!-- Или через th:classappend (предпочтительный способ) -->
<a th:classappend="${activePage == 'dashboard'} ? 'nav-item--active'" class="nav-item">
```

### Шахматка: все CSS-классы собираются в контроллере

OccupancyGridController пре-вычисляет cssClasses для DayHeader, Row (labelCssClasses), Cell (cssClasses). В шаблоне только `th:class="${c.cssClasses}"`. Любая сборка классов в шаблоне через конкатенацию или pipe-syntax ломается — Thymeleaf-парсер спотыкается при итерации по вложенным записям. Если нужно добавить класс — делайте это в контроллере через StringBuilder.

### Pre-computed CSS в контроллерах

Контроллеры передают готовые CSS-значения (цвета, флаги, иконки) в модель — не вычислять в шаблоне. Так шаблон остаётся читаемым, а логика в Java где её легче тестировать.

Пример:
```java
model.addAttribute("statusColor",
    status.equals("BOOKED") ? "var(--green)" : "var(--text-muted)");
```

### Multi-tenant изоляция

Каждая важная таблица имеет `tenant_id`. **Все репозитории и сервисы фильтруют по `AuthContext.tenantId()`**. Нельзя делать `findAll()` без фильтра — утечка данных между tenants.

### PdAnonymizer

Имена гостей автоматически анонимизируются до инициала. Мы не оператор ПД, не берём согласия (кроме отдельных случаев типа отзывов — там checkbox обязателен).

### Миграции обязательны

Не полагаться на Hibernate `ddl-auto=validate`. Схема меняется только через Flyway V-миграции. Файлы в `src/main/resources/db/migration/`.

### Автопилот не пушит

AI-режим — только рекомендации. `PricingEngine.runForProperty` считает рекомендации и пишет их в журнал решений; отправки цен наружу в коде нет. Никаких автоматических `pushPrices()` или `pushAvailability()` без явного действия пользователя. Это юридическая защита.

### ChannelAdapter capability-методы

Интерфейс `ChannelAdapter` имеет методы `supportsPull()`, `supportsIcalExport()`, `supportsPush()`, `supportsBookingDetails()`. Все — с дефолтными значениями, которые существующие адаптеры (ICAL/MANUAL) не переопределяют. При добавлении API-канала (Avito в будущем) переопределяются выборочно.

Любой код, вызывающий `pushPrices()` / `pushMinStay()`, должен сначала проверить `supportsPush()`. Безопасно вызывать и без проверки (дефолт — no-op), но лучше явно.

### Ручные записи в многоканальной среде (эхо)

Ручная запись (`calendar_blocks.channel_id IS NULL`: ручная бронь, ремонт, личное использование, hold из шахматки) экспортируется в iCal-фиды **всех** активных каналов. Многие площадки и channel manager'ы (RC, вероятно Циан, DomClick) возвращают импортированную блокировку обратно в своём фиде. Если создать по ней `CalendarBlock`, он переживёт удаление исходной записи, а при двух каналах начнёт поддерживать сам себя. Защита (V26), в дополнение к anti-echo по `channel_id` в экспорте:

- **UID-маркер.** Ручная запись уходит в фид с UID `optirent-manual-<id>@optirent.ru` (`CalendarBlock.MANUAL_UID_PREFIX`). Входящее событие с таким префиксом — наше эхо, блокировка не создаётся.
- **Эвристика по датам и «тени»** (V27) — для каналов, которые подменяют UID (RC). Новое для нас событие, чьи даты точно совпадают с живой ручной записью, заведённой не раньше 7 дней назад (`ICalChannelAdapter.ECHO_MATCH_WINDOW_DAYS`), — вероятно эхо, но может быть и настоящей бронью. Поэтому оно **импортируется** обычной блокировкой канала с пометкой `calendar_blocks.shadow_of_manual_id`. Только для категорий с `unit_count = 1`. Блокировки, импортированные раньше, задним числом тенью не становятся; если площадка сдвинула даты — пометка снимается.
- **Тень живой ручной записи не показывается, не считается занятостью и не уходит в экспорт** — `AvailabilityService.withoutActiveShadows` (из пары остаётся ручная запись: на ней сумма и гость). Когда ручную запись удаляют, тень остаётся обычной блокировкой канала и держит даты, пока событие есть в фиде площадки, — защита от овербукинга. Снять её хост может через «Открыть даты». Код, который читает блокировки мимо `AvailabilityService`, должен вызывать `withoutActiveShadows` сам.
- **`manual_block_echoes`** хранит связь «(channel_id, external_uid) → ручная запись» для обоих видов эха — нужна для предупреждения при удалении.
- **Удаление ручной записи — мягкое**: `calendar_blocks.cancelled_at`. Строка 90 дней (`ManualBlockRetentionService`) не считается занятостью, уходит в экспорт со `STATUS:CANCELLED` (выключается `APP_ICAL_EXPORT_CANCELLED=false`) и держит эхо-связи, чтобы копия у площадки не вернулась блокировкой. `findByUnitTypesInRange` и `findOverlapping` такие строки отфильтровывают. Любой новый запрос к `calendar_blocks` обязан учитывать `cancelled_at`.
- **При удалении ручной записи её тени скрываются автоматически** (`ignored = true`, как кнопкой «Открыть даты»), если нет признаков настоящей брони: UID технический (число, hex, UUID — `GridActionController.looksTechnicalUid`) и за тенью нет брони с именем гостя. Тени физически не удаляются — вернуть можно в модалке дня, «Устранить блокировку» → «Закрыть снова». Хост видит только «Запись удалена». Модалка-предупреждение показывается, лишь когда тень с признаками настоящей брони оставлена закрывать даты.
- **Кнопка «Открыть даты» убрана из основного сценария**: блокировки площадок в модалке дня показываются без кнопок, действия с ними — в свёрнутом блоке «Устранить блокировку».
- **Площадки и `STATUS:CANCELLED`.** По наблюдениям на пилоте текущие площадки (RC, Циан, DomClick) не снимают у себя блокировку по `STATUS:CANCELLED` в нашем фиде. OptiRent делает best-effort: 90 дней отдаёт отменённое событие (`STATUS:CANCELLED`, `SEQUENCE:1`, `TRANSP:TRANSPARENT`) и скрывает тень у себя. `METHOD:CANCEL` внутри VEVENT не пишем: по RFC 5545 METHOD — свойство календаря, строгий парсер может отбросить весь фид. Не проверено обратное объяснение: площадка, не читающая STATUS, может считать само присутствие события занятостью — тогда блокировку держит именно наш фид; проверяется `APP_ICAL_EXPORT_CANCELLED=false`. Гарантированное удаление брони на площадке возможно только через её нативный API — задача следующей итерации.

- **Заявки и брони с виджета — тоже «свои» записи** (V29), на них действует всё перечисленное. UID в экспорте — `optirent-widget-<UUID заявки>@optirent.ru` (`CalendarBlock.WIDGET_UID_PREFIX`), один и тот же для резерва, подтверждённой брони и отмены. У заявки есть блок-«якорь» канала виджета на всё время жизни брони: `HOLD` до ответа хозяина, `WIDGET_BOOKING` после подтверждения; к нему привязываются эхо-связи и тени. Занятость подтверждённой брони даёт сама бронь — якорь `WIDGET_BOOKING` `withoutActiveShadows` отбрасывает. Отклонение, истечение и отмена — мягкое удаление якоря (`WidgetBookingService.close`) со скрытием теней (`EchoShadowService` — общий для шахматки и виджета). «Своя запись» в коде — `CalendarBlock.isOwn()`, а не `isHandMade()`.

Все пропуски и привязки пишутся в лог (`Skipped echo of own MANUAL booking` / `… WIDGET booking`, `Linked echo from channel`). Подробности и порядок выкладки — `patches/INTEGRATION_MANUAL_ECHO.md`.

### Виджет бронирования: публичный API и встраивание

Идёт переделка виджета (v2) по фазам — `patches/INTEGRATION_BOOKING_WIDGET_V2.md`. Все семь фаз сделаны (V29–V32).

- API — `/api/widget/{slug}/…` (`WidgetApiController`), ключ — `booking_widgets.slug`. Страница бронирования — `/b/{slug}`, для фрейма — `/b/{slug}/embed` (`WidgetPublicController`). Прежние `/book/{secret}` и `/widget/{secret}` — постоянные редиректы (301) на них, `/widget.js` подменяет старую вставку новым компонентом. **Эти три адреса не удалять и срок им не ставить**: хозяева уже разослали ссылки гостям.
- На `/b/{slug}` настройки виджета встроены в страницу (`WidgetConfigService.inlineJson`), поэтому он рисуется без запроса и без сдвигов вёрстки. Всё, что влияет на высоту до загрузки календаря, должно занимать место заранее — иначе CLS перестанет быть нулём.
- Сумму считает только сервер: `WidgetPricing` (ночи → скидка за длительность → промокод → уборка). Правила дат и состояния дней календаря — `WidgetCalendar`. Оба без БД, тестируются напрямую.
- Ошибки API — `{code, message}`, коды в `WidgetError`. Новая причина отказа — новый код, а не строка в контроллере.
- CORS — по списку `booking_widgets.allowed_origins` (`WidgetCorsConfig`), тем же списком страница `/widget/{secret}` отдаёт `frame-ancestors`. `@CrossOrigin` на эндпоинты виджета не ставить.
- Капчи нет. Защита от спама: honeypot, лимит по IP, `WidgetFormToken` (время заполнения формы).
- Режимы: `REQUEST` (резерв `hold_minutes`, по умолчанию сутки) и `INSTANT`. Бронь создаётся под `SELECT … FOR UPDATE` по строке категории.
- `booking_widgets.cleaning_fee` — сбор с гостя. Настройка `cleaning_cost` — расход хозяина, в цену для гостя не входит.
- Сам виджет (фаза 2) — Web Component `<optirent-booking>` с Shadow DOM, исходники в `widget/src`, сборка `cd widget && npm run build` → `static/w.js`. Бандл лежит в репозитории; `WidgetBundleTest` падает, если он собран не из текущих исходников, — после правок в `widget/` пересобрать и закоммитить `w.js`. Внутри только ванильный JS, без фреймворков; бюджет 35 КБ JS + 15 КБ CSS (gzip). Тексты хозяина вставляются только через `textContent`. Посмотреть виджет — в конструкторе или на `/b/{slug}`.
- Фото страницы бронирования (фаза 3, V30) загружаются к нам: `PhotoProcessor` поворачивает по EXIF и режет варианты 480/960/1600 в JPEG и WebP (WebP — утилитой `cwebp`, в образе пакет `libwebp-tools`; без неё только JPEG), `WidgetPhotoService` пишет их в каталог `APP_UPLOADS_DIR` — в docker это том, **его нужно бэкапить вместе с дампом БД**. Отдаёт `/media/widget/**`. Оригинал и EXIF не хранятся.
- Раскладка и оформление (фаза 4, V31) — в `booking_widgets.config_json`: пресет (`split` / `vertical` / `horizontal` / `compact` / `custom`), скрытые блоки, цвет, скругление, шрифт. Всё, что туда пишется и оттуда отдаётся, проходит `WidgetLayout.normalize` — мимо него в `config_json` не писать. Как пресет превращается в сетку, считает `widget/src/layout.js`; цвета из цвета хозяина — `widget/src/theme.js`. Шрифты виджета лежат в `static/fonts` (OFL), внешних шрифтов не подключать.
- Конструктор (фаза 5) — `/settings/widgets/{id}/design`, скрипт `static/js/widget-designer.js` (без сборки). Превью — настоящий виджет во фрейме `/settings/widgets/{id}/frame`, несохранённые настройки передаются методом компонента `preview(layout, mode)`. Сохранение — `POST …/design` с JSON, который обязательно проходит `WidgetLayout.normalize`. Страница бронирования с новым виджетом — `/b/{slug}`.
- Аналитика (фаза 6, V32): виджет шлёт шаги воронки на `POST /api/widget/{slug}/event`, `WidgetFunnelService` копит счётчики в `widget_funnel_daily` — только агрегаты, без IP, cookie и идентификаторов посетителей; так и оставить, иначе это станет обработкой ПД. Страница — `/settings/widgets/{id}/stats`; заявки и выручка на ней считаются по броням, воронка — по событиям. `booking_widgets.metrika_id` — счётчик Метрики хозяина, виджет шлёт в него цели `optirent_<шаг>`.
- Письма гостю — `EmailService.send`, включается `SMTP_HOST` + `MAIL_FROM`; без них пропускаются.

### Подписи в настройках

Подписи и пояснения полей на /settings задаются в `SettingsController.FIELDS`, а не берутся из `system_settings.description` (у настроек, созданных при регистрации, описание пустое). Новый ключ настройки на странице — новая запись в `FIELDS`, иначе шаблон упадёт на `fields[setting.key]`.

### Локальная разработка не настроена, работаем через staging

У Григория локально нет docker и БД. Поведение приложения проверяется через автодеплой на staging.optirent.ru. Поэтому:

- Unit-тесты и компиляция локально работают: `mvn -o test` (системный Maven + заполненный `~/.m2`). `WidgetBookingIntegrationTest` поднимает PostgreSQL в контейнере (Testcontainers) и без docker пропускается; с colima — см. `patches/INTEGRATION_BOOKING_WIDGET_V2.md`. Он же прогоняет все миграции на пустой базе. Приложение целиком локально не поднять — нет docker/БД; Flyway, шаблоны и интеграции проверяются только на staging.
- Ошибка компиляции (в том числе тестов) блокирует docker-билд и деплой. Перед push прогонять `mvn -o test`.
- Для sanity-проверок синтаксиса можно использовать `docker run --rm -v "$PWD":/app -w /app maven:3.9-eclipse-temurin-21 mvn compile` (на маке docker не установлен, но так на сервере через SSH).

### CSRF-токен и перезапуски staging-контейнера

Сессии живут в памяти Tomcat. При каждом деплое staging контейнер перезапускается → все CSRF-токены в открытых вкладках становятся невалидными → любой POST даёт 403. Workaround — hard reload (⌘⇧R) или инкогнито-окно после каждого деплоя. Правится переходом на CookieCsrfTokenRepository (отложено).

---

## Приоритеты спринта (блоки 4.5 - 4.8)

### Что уже сделано (feat/channels-mvp)

- **Блок 1** (V16): unit_types, channels, calendar_blocks, расширение bookings
- **Блок 2** (V17): Channel abstraction + iCal (parser/writer/fetcher/adapter), AvailabilityService, public /ical/{secret}.ics
- **Блок 3**: Шахматка /calendar/grid v1 (визуализация занятости + группировка по property)
- **Блок 4.1**: SSRF-защита HttpICalFeedFetcher через UrlSafetyGuard, фикстурные тесты парсера (Sutochno/Ostrovok/Twil)
- **Блок 4.2** (V18): unit_types backfill + UI управления категориями + reconcile idempotency tests
- **Блок 4.3**: UI /settings/channels, ChannelSyncSchedulerService с jitter (±60с, min 15 мин, default 30 мин), ChannelRateLimiter (60s cooldown), регенерация export_secret
- **Блок 4.4** (V19): расширение ChannelAdapter (supportsPush, supportsBookingDetails, pushPrices, pushMinStay — все no-op по умолчанию), bookings.data_source, planned_prices, EffectivePriceService, интерактивная шахматка (клик по ячейке → модалка с закрытием дней и установкой цен)
- **Блок 4.5**: расширенная шахматка (горизонт до 366 дней, мини-календарь, стрелки, клавиатура, hover-строка, индикаторы конфликтов)
- **Блок 4.6**: главная с hero-метриками, шахматкой-виджетом, графиком выручки и потенциалом оптимизации
- **Блок 4.7** (V20): `alert_events`, `channels.consecutive_errors`, ConflictDetector + AlertSchedulerService (раз в 5 мин), TelegramService, карточка Telegram и журнал на /settings/integrations
- **Блок 4.8** (V21): инструменты пилота — журнал обращений к iCal-экспорту (`channel_feed_fetches`), страница /settings/channels/diagnostics (интервал опроса площадкой, задержка «ручная запись → площадка забрала фид»), алерты типа `OVERLAP` (наложение без ручной записи, только в журнал). После входа пользователь попадает на /calendar/grid — шахматка считается главной функцией продукта.
- **Блок 4.9** (V22): Booking Widget MVP — `booking_widgets`, `ChannelType.WIDGET`, WidgetBookingService (hold + бронь PENDING), публичные /book/{secret}, /widget/{secret}, /widget.js, админка /settings/widgets, заявки /bookings/pending. Отступления от плана ниже и непроверенное — в `patches/INTEGRATION_BLOCK4_9.md`. Заодно: раздел /staff «Сотрудники» (ссылка и PIN горничной), починена вёрстка «Отзывов».
- **Цвета каналов** (V23): `channels.color` из фиксированной палитры `ChannelPalette`; занятый день в шахматке закрашен цветом канала с первой буквой его названия, закрытый вручную — чёрный. См. `patches/INTEGRATION_CHANNEL_COLORS.md`.
- **Открытие дат, закрытых площадкой** (V25): `calendar_blocks.ignored` — блокировку с канала нельзя удалить (вернётся из фида), поэтому хост помечает её в модалке шахматки «Открыть даты»; такие блокировки не считаются занятостью и не уходят в экспорт. Запрос `findByUnitTypesInRange` их отфильтровывает, `findOverlapping` — нет.
- **Виджет бронирования v2, фаза 7**: страница `/b/{slug}` с превью для мессенджеров и встроенными настройками, фрейм с авто-высотой, редиректы со старых адресов; прежний виджет удалён.
- **Виджет бронирования v2, фаза 6** (V32): воронка по шагам и источникам, цели Яндекс.Метрики, страница статистики.
- **Виджет бронирования v2, фаза 5**: конструктор с живым превью, перетаскиванием блоков и копированием кода; страница `/b/{slug}`.
- **Виджет бронирования v2, фаза 4** (V31): блоки, пресеты компоновки, цвет / скругление / шрифт / тема, удобства и ссылка на карту.
- **Виджет бронирования v2, фаза 3** (V30): загрузка и обработка фото, том под загрузки, галерея с полноэкранным просмотром.
- **Виджет бронирования v2, фаза 2**: Web Component `<optirent-booking>` (`widget/`, бандл `static/w.js`), предпросмотр в настройках виджета.
- **Виджет бронирования v2, фаза 1** (V29): публичный API `/api/widget/{slug}`, расчёт суммы на сервере, промокоды, скидки за длительность, режим мгновенной брони, эхо-защита броней виджета, CORS по списку сайтов. См. `patches/INTEGRATION_BOOKING_WIDGET_V2.md`.
- **Эхо ручных записей** (V26, V27): UID-маркер `optirent-manual-*`, `manual_block_echoes`, мягкое удаление ручных записей (`calendar_blocks.cancelled_at`, `STATUS:CANCELLED` в экспорте 90 дней), «тени» для эха по датам (`calendar_blocks.shadow_of_manual_id`). См. раздел «Ручные записи в многоканальной среде» и `patches/INTEGRATION_MANUAL_ECHO.md`.

### Что в работе / приоритет

**Блок 4.8 — Пилот на Садовой (операционная часть)**
- Отключить одну площадку в RC-кабинете, подключить через iCal в OptiRent
- Снять замеры со страницы диагностики, проверить журнал на эхо площадки
- Порядок действий — `patches/INTEGRATION_BLOCK4_8.md`

### Отложено

- **AvitoClient + AvitoChannel** — client_id/secret получены, но интеграцию по API решили не делать сейчас. Переход на схему с API произойдёт одновременно со всеми площадками, когда ICal-путь будет стабилен. Capability-методы в ChannelAdapter уже готовы к этому.
- **Полноценная CI с прогоном тестов** — отдельным мелким PR. Сейчас в Dockerfile `./mvnw package -DskipTests`, т.е. тесты не гоняются ни в CI, ни при деплое.
- **Пилот на Суточно** — в активной фазе: импорт фида Суточно подключён на staging для Садовой (property_id=5), жду ответа саппорта по тому, как отдать нашу ссылку обратно в Суточно (у них скрыта опция ручного импорта iCal, когда объект синхронизирован через channel manager).

---

## Блок 4.9 — Booking Widget MVP (сделан; ниже исходный план)

**Статус: этот раздел — исторический.** MVP (V22) полностью заменён виджетом v2 (V29–V32): прежний интерфейс на flatpickr, hCaptcha и API по секрету удалены, адреса `/book/{secret}`, `/widget/{secret}` и `/widget.js` остались постоянными редиректами на новый виджет. Что есть сейчас — раздел «Виджет бронирования: публичный API и встраивание» выше и `patches/INTEGRATION_BOOKING_WIDGET_V2.md`. Ниже — исходный план MVP: полезен как объяснение, зачем виджет и почему он канал, но не как описание кода.

### Назначение

Виджет / страница бронирования для хоста. Три способа использования, один backend. Гости отправляют заявки на бронь напрямую, минуя комиссии площадок. Хост получает уведомление, связывается с гостем, подтверждает в админке → автоматический экспорт в iCal всех каналов (anti-echo из коробки).

### Три способа использования — равнозначны

Хосты 1-2 квартир часто **не имеют своего сайта** — их канал общения с клиентами это Instagram DM, WhatsApp, Telegram. Поэтому прямая ссылка — первичный сценарий, виджет на сайте — вторичный. Один `BookingWidget` entity обслуживает все три:

**1. Прямая ссылка (landing-страница)** — для рассылки клиентам в мессенджерах. Хост даёт URL в WhatsApp/Telegram/Instagram DM, клиент тыкает — открывается полноценная страница с метаданными, OpenGraph preview, описанием объекта, фото.

URL: `GET /book/{secret}`

Обязательно:
- OpenGraph теги для красивого preview при расшаривании (это критично для конверсии в мессенджерах)
- Title, описание объекта, фото (если хост загрузил)
- Правила заселения, checkin/checkout время, cancellation policy
- Контакты хоста опционально (можно скрыть до подтверждения)

**2. Встраиваемый виджет (iframe)** — для сайта хоста. Минимальная HTML-обёртка без лишних метаданных.

URL: `GET /widget/{secret}` → вставка через iframe:
```html
<iframe src="https://optirent.ru/widget/{secret}"
        width="100%" height="600" frameborder="0"></iframe>
```

**3. JS-вставка** — для глубокой интеграции в сайт хоста. Загружает виджет в div без iframe.

```html
<div id="optirent-widget" data-secret="abc123xyz"></div>
<script src="https://optirent.ru/widget.js" async></script>
```

Все три используют одни и те же API-endpoints для availability/price/request. Отличается только HTML-обёртка.

### Архитектурное обоснование — виджет как ещё один Channel

Виджет — это `ChannelType.WIDGET`. Все заявки/брони проходят через существующую Channel abstraction. Это значит:

- Брони автоматически попадают в шахматку
- Экспортируются в iCal других каналов через excludeChannelId
- Учитываются в статистике и AI-рекомендациях
- Используется AvailabilityService для расчёта свободных дат

Не плодить параллельных моделей.

### Три режима работы

1. **REQUEST** (MVP) — заявка. Создаётся CalendarBlock HOLD на 24ч + Booking со status=PENDING. Хост подтверждает/отклоняет вручную. Никаких платежей на нашей стороне.
2. **HOLD** — то же, но hold-период настраивается.
3. **PAY** (далеко) — интеграция с ЮKassa/Robokassa, автоматическая бронь после оплаты.

MVP = только REQUEST.

### UI — flatpickr

Для календаря использовать **flatpickr** (flatpickr.js.org):
- `mode: "range"` — выбор check-in/check-out одним пикером
- `disable: [...]` — массив занятых дат из AvailabilityService
- `minDate: "today"` + `maxDate` по booking_window_days
- Встроенная русская локаль
- MIT, ~15KB gzipped, vanilla JS

Положить self-hosted в `src/main/resources/static/libs/flatpickr/`, чтобы виджет не падал если CDN лёг (критично — виджет работает на чужих сайтах).

### Миграция (исходный план; фактическая — V22__booking_widgets.sql)

```sql
CREATE TABLE booking_widgets (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    channel_id BIGINT NOT NULL REFERENCES channels(id),
    secret VARCHAR(64) UNIQUE NOT NULL,         -- 192 бита, base64url
    title VARCHAR(255) NOT NULL,
    unit_type_ids BIGINT[] NOT NULL,             -- в MVP один, модель расширяема
    min_nights INTEGER NOT NULL DEFAULT 1,
    max_nights INTEGER NOT NULL DEFAULT 30,
    booking_window_days INTEGER NOT NULL DEFAULT 180,
    checkin_time TIME NOT NULL DEFAULT '14:00',
    checkout_time TIME NOT NULL DEFAULT '12:00',
    mode VARCHAR(20) NOT NULL DEFAULT 'REQUEST',
    hold_hours INTEGER NOT NULL DEFAULT 24,
    show_price BOOLEAN NOT NULL DEFAULT true,
    show_powered_by BOOLEAN NOT NULL DEFAULT true,
    custom_css TEXT,
    theme VARCHAR(20) DEFAULT 'light',           -- light, dark, auto

    -- Поля для landing-режима (GET /book/{secret})
    description TEXT,
    rules TEXT,                                   -- правила заселения
    cancellation_policy TEXT,
    address_hint VARCHAR(255),                    -- "Центр, у метро X" — без точного адреса
    photos_json JSONB,                            -- массив URL фото (пока ссылки, позже свой сторадж)
    show_host_contact BOOLEAN DEFAULT false,

    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_booking_widgets_secret ON booking_widgets(secret);
CREATE INDEX idx_booking_widgets_tenant ON booking_widgets(tenant_id);
```

Расширить `ChannelType` enum: добавить `WIDGET`.
Для Booking: `source = 'DIRECT_WIDGET'`, `external_id = UUID заявки`.

Поля для landing-режима опциональны. Если хост их не заполняет, landing-страница просто без них (минимальный вид = как iframe-виджет).

### Endpoints

**Админские (под auth, tenant isolation):**
```
GET  /settings/widgets                  - список
POST /settings/widgets/create           - создать (+ auto-create Channel)
GET  /settings/widgets/{id}             - просмотр + embed code
POST /settings/widgets/{id}/update
POST /settings/widgets/{id}/regenerate  - новый secret (ломает старые ссылки)
POST /settings/widgets/{id}/delete      - soft delete
```

**Публичные (без auth, rate-limited 60/мин на IP):**
```
GET  /book/{secret}                     - полная landing-страница (OpenGraph, фото, описание)
GET  /widget/{secret}                   - минимальная HTML-обёртка (для iframe)
GET  /widget.js                         - JS-лоадер для вставки через <script>
GET  /widget/{secret}/availability?from=&to=
     → { busyDays: [...], minNights, maxNights, maxDate }
GET  /widget/{secret}/price?from=&to=&guests=
     → { price, nights, breakdown: [...] }
POST /widget/{secret}/request
     body: { from, to, guests, name, phone, email, note, captchaToken }
     → { status, holdExpiresAt, message }
```

Все три frontend-варианта (`/book/`, `/widget/`, `/widget.js`) используют одни и те же `/availability`, `/price`, `/request`.

### OpenGraph для landing-страницы

Критично для конверсии — когда хост скидывает ссылку в WhatsApp/Telegram, мессенджер делает preview с картинкой и заголовком. Без OpenGraph preview выглядит убого (просто URL).

```html
<meta property="og:title" th:content="${widget.title}">
<meta property="og:description" th:content="${widget.description}">
<meta property="og:image" th:content="${firstPhoto}">
<meta property="og:url" th:content="${currentUrl}">
<meta property="og:type" content="website">
```

### Обработка заявки (REQUEST)

```
POST /widget/{secret}/request
  ↓
1. CAPTCHA проверка (hCaptcha)
2. Валидация формы (даты, контакты, min_nights)
3. Проверка доступности через AvailabilityService (anti-race)
4. Транзакция:
   - CalendarBlock { type=HOLD, channel_id=widget, expires_at=now+hold_hours }
   - Booking { status=PENDING, channel_id=widget, external_id=UUID,
               guest_name (анонимизированный!), guest_phone (с согласием) }
5. Уведомление хосту: email + Telegram (с кнопками Подтвердить/Отклонить)
6. Ответ гостю
```

Админка: страница `/bookings/pending` — список заявок, кнопки Подтвердить/Отклонить. Подтверждение → status=BOOKED, HOLD снимается, бронь в iCal export.

### Anti-echo в iCal

- HOLD-блокировки от виджета **экспортируются** в iCal других каналов
- Подтверждённые брони (BOOKED) тоже экспортируются
- Отклонённые / истёкшие HOLD — физически удаляются
- Экспорт в iCal самого виджета не нужен (виджет сам читает из AvailabilityService)

### Admin UI — три секции кода для встраивания

На странице виджета показывать все три варианта в табах:

1. **Прямая ссылка** — `https://optirent.ru/book/{secret}` + кнопка "Копировать" + подпись "Отправьте эту ссылку в WhatsApp/Telegram/Instagram"
2. **Встраивание на сайт** — iframe-код + кнопка "Копировать" + подпись "Вставьте этот код на свой сайт в любое место"
3. **Advanced JS** — JS-вставка + кнопка "Копировать" + подпись "Для разработчиков: гибкая интеграция в сайт"

### Rate limiting + защита

- IP-based rate limit 60/мин на публичные endpoints
- hCaptcha на /request (не SmartCaptcha — приватнее, не зависит от Яндекса, работает на зарубежных IP)
- CSRF стандартный на POST
- Валидация дат: from < to, from >= today, to <= today + window
- AvailabilityService проверка ПЕРЕД созданием HOLD (защита от гонки)

### Что НЕ в MVP

- Платежи (это Mode=PAY)
- Автоматическое подтверждение
- CSS-редактор кастомизации (пока theme light/dark + custom_css как текст)
- Статистика виджета (список заявок хватит)
- A/B варианты
- Multi-unit выбор в одной заявке
- Виджет на нескольких языках (только русский)
- Собственный сторадж фото (пока хост даёт прямые URL)

### Открытые вопросы

1. **Таймзоны:** у нас всё в Europe/Moscow. В виджете показывать время по таймзоне объекта или гостя?
2. **Антифрод:** нужен ли blocklist для повторных спам-заявок с одного IP с разными именами?
3. **"Powered by OptiRent":** бесплатно = с бейджем, за $5/мес = без. На лендинге пока не анонсируем.
4. **Предоплата через виджет:** можно добавить поле "комментарий для гостя" где хост пишет реквизиты — быстрый workaround до PAY-режима.
5. **Фото:** в MVP — прямые URL (хост сам хостит на Яндекс.Диске/Imgur). Позже свой сторадж через S3-compatible storage.

### Стратегическое значение

На лендинге формулировка:
> "Получите красивую страницу бронирования со своей ссылкой. Отправляйте её клиентам в Instagram, WhatsApp, Telegram, или встраивайте на сайт. Прямые брони без комиссии площадок. Бесплатно."

Создаёт network effect через "Powered by OptiRent" на страницах/сайтах. Первый лёгкий путь к будущей монетизации (платная опция без бейджа) без ломки бесплатного ядра.

### Пустая ниша на рынке

Западные решения (Lodgify, Hostaway, Beds24, BookingSync, OwnerRez) — платные $20-200/мес, виджет зашит в комплект с channel manager.

Российские (ТУРЫ.РФ, TravelLine, Bnovo, RealtyCalendar) — для отелей/агентств, дорого, сложно.

**Простого бесплатного виджета/страницы бронирования для хоста 1-2 квартир с TG-каналом нет.** Это ниша OptiRent.

---

## Нюансы деплоя

### Три окружения на одном сервере

| Домен | Порт | Ветка | Директория |
|---|---|---|---|
| optirent.ru | 8080 | main | /opt/rentoptima |
| staging.optirent.ru | 8081 | feat/channels-mvp | /opt/rentoptima-staging |
| partner.optirent.ru | 8082 | feat/design-refresh (и др.) | /opt/rentoptima-partner |

На staging и partner — basic auth через nginx htpasswd.

### Автодеплой

- `.github/workflows/deploy.yml` → main → prod
- `.github/workflows/deploy-staging.yml` → feat/channels-mvp → staging
- `.github/workflows/deploy-partner.yml` → партнёрские ветки → partner

GitHub Actions делает SSH на сервер → запускает `scripts/deploy.sh` (или аналог) → `git pull` + `docker compose up --build -d`.

### Известные баги деплоя

**Баг 1 — сравнение SHA в deploy.sh**

Скрипт `/opt/rentoptima-*/scripts/deploy.sh` сравнивает `CURRENT_SHA` и `LATEST_SHA` **до** `git fetch`, из-за чего иногда пропускает деплой изменений. Workaround — `git fetch origin branch` перед сравнением (уже исправлено в staging и partner версиях, в prod ещё старый). Отдельная грань бага: если прошлый `docker compose up --build` упал, SHA уже обновился в git, и следующий запуск скрипта думает «нечего делать» — надо руками `docker compose up --build -d`.

**Баг 2 — тесты не прогоняются в CI**

Dockerfile собирает `./mvnw package -DskipTests`. Нужно: либо прогонять тесты в GitHub Actions отдельным шагом перед SSH-деплоем, либо убрать `-DskipTests` из Dockerfile (замедлит сборку, но ловит регрессии). Пока это в бэклоге — любая ошибка в тестах блокирует билд (test-compilation не skip'ается), поэтому важно писать тесты аккуратно.

**Баг 3 — Partner docker-compose перезаписывается**

На /opt/rentoptima-partner `git reset --hard` затирает кастомный docker-compose.yml (с портом 8082). Workaround — `git update-index --assume-unchanged docker-compose.yml` + `cp docker-compose.partner.local.yml docker-compose.yml` в deploy скрипте.

### Логи деплоев

- `/var/log/optirent-staging-deploy.log`
- `/var/log/optirent-partner-deploy.log`

---

## Контактные URL

- **Prod:** https://optirent.ru
- **Staging:** https://staging.optirent.ru (basic auth: admin)
- **Partner:** https://partner.optirent.ru (basic auth: admin, basic auth у приятеля)
- **GitHub:** https://github.com/GregoryError/rent_pilot (private)
- **Actions:** https://github.com/GregoryError/rent_pilot/actions

### Сервер

`server-khqi` = 94.183.236.144 (Debian 12, 4GB RAM, Docker)

- `/opt/rentoptima` — prod
- `/opt/rentoptima-staging` — staging
- `/opt/rentoptima-partner` — partner

---

## Для Claude Code — чеклист перед работой

Когда начинаешь новую задачу в этом репо:

1. Прочитать этот файл (ты уже это сделал если видишь текст).
2. Проверить какая активная ветка: обычно работаем на feat/channels-mvp или фичевой ветке от неё. Main защищена (но Григорий как админ может пушить).
3. Если задача про Property или bookings — помнить про двойной маппинг tenant_id.
4. Если про шаблоны — pipe-syntax или th:classappend, не плюсы. Для шахматки — все классы в контроллере, не в шаблоне.
5. Если про миграции — обязательно V-номер больше последнего (сейчас V32), не удалять поля из существующих.
6. Если про UI — следовать существующей стилистике (CSS vars из core.css). По умолчанию тема светлая (`static/js/theme.js` ставит `data-theme="light"`, тёмная — только по выбору пользователя); любая автономная страница без layout должна подключать `theme.js`. Проверять обе темы.
7. Если делаешь блок — завершить INTEGRATION_<N>.md в patches/ с инструкциями по применению.
8. **Git-операции — предлагай, но не выполняй сам.**
    - Правь файлы напрямую.
    - Для `git add` / `git commit` / `git push` — предлагай команду, жди подтверждения.
    - Не трогай main-ветку никогда.
    - На feat-ветках коммитить можно, но только с чётким сообщением и после одобрения.
9. После пуша — помнить про CSRF-грабли (hard reload / incognito в staging).
10. При падении деплоя — сначала проверить баг-1 (контейнеру > 2 мин? образ пересобрался?), только потом искать баги в коде.
