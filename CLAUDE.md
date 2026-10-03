# CLAUDE.md

Проектный контекст для Claude Code. Читается автоматически в начале каждой сессии.

---

## Что за проект

**OptiRent** (в репе — RentOptima) — веб-сервис для оптимизации короткосрочной аренды. Целевая аудитория: хосты 1-2 объектов, ведущие брони "в блокноте". Позиционирование — бесплатный сервис через сообщества.

Ключевая функция: iCal-хаб для синхронизации бронирований между площадками (Avito, Sutochno, Ostrovok, Twil, Booking) + AI-рекомендации цен. Автопилот работает в режиме советника без автоматического пуша.

Проект изначально работал поверх RealtyCalendar (`rc_object_id` в Property, креды в system_settings). В feat/channels-mvp идёт переход на прямые интеграции площадок. RC-код сохранён как тонкая обёртка `RcChannelAdapter` для обратной совместимости.

---

## Стек

- Java 21
- Spring Boot 3.3.2 (Spring MVC + Thymeleaf + Spring Security + Spring Data JPA)
- PostgreSQL 16
- Flyway (миграции V1..V19+)
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

AI-режим — только рекомендации. Никаких автоматических `pushPrices()` или `pushAvailability()` без явного действия пользователя. Это юридическая защита.

### ChannelAdapter capability-методы

Интерфейс `ChannelAdapter` имеет методы `supportsPull()`, `supportsIcalExport()`, `supportsPush()`, `supportsBookingDetails()`. Все — с дефолтными значениями, которые существующие адаптеры (ICAL/RC/MANUAL) не переопределяют. При добавлении API-канала (Avito в будущем) переопределяются выборочно.

Любой код, вызывающий `pushPrices()` / `pushMinStay()`, должен сначала проверить `supportsPush()`. Безопасно вызывать и без проверки (дефолт — no-op), но лучше явно.

### Локальная разработка не настроена, работаем через staging

У Григория локально нет docker/maven. Все изменения проверяются через автодеплой на staging.optirent.ru. Поэтому:

- Прогон тестов локально невозможен — надейтесь на CI (который пока их не гоняет, см. "Известные баги 2").
- Compilation errors проявятся только в GitHub Actions при docker-билде. Это блокирует деплой. Проверяйте код глазами перед push.
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

### Что в работе / приоритет

**Блок 4.5 — Расширенная шахматка**
- Произвольный горизонт (убрать cap 62 дня)
- Мини-календарь для прыжка на месяц
- Фиксированные стрелки прокрутки по бокам
- Keyboard navigation (стрелки, Enter, Space)
- Hover sticky-строка внизу с деталями дня
- Индикаторы конфликтов (красная рамка если iCal + ручная бронь на одном дне)

**Блок 4.6 — Редизайн главной с шахматкой во всю ширину**
- Hero-метрики (прогноз выручки / занятость / количество броней / потенциал)
- Шахматка как центральный виджет на /dashboard
- Виджет "Потенциал оптимизации" в стиле AirDNA/Beyond Pricing (базовая цена пользователя vs рекомендованная системой)
- График выручки по дням (прошлое — сплошные столбцы, будущее — пунктир)
- Статусы каналов компактной строкой
- Прогноз выручки с явной пометкой "estimated" на карточках и графиках

**Блок 4.7 — Telegram-алерты + детектор конфликтов**
- Scheduled task раз в N минут — поиск пересечений между Booking и CalendarBlock одного unit_type
- Telegram-бот с токеном в system_settings
- Уведомление tenant-админу с ссылкой на /calendar/grid
- Также алерты о падении шедулера канала (last_error N раз подряд)

**Блок 4.8 — Пилот на Садовой**
- Отключить одну площадку в RC-кабинете, подключить через iCal в OptiRent
- Замер задержки распространения занятости
- Логировать все пересечения дат

### Отложено

- **AvitoClient + AvitoChannel** — client_id/secret получены, но интеграцию по API решили не делать сейчас. Переход на схему с API произойдёт одновременно со всеми площадками, когда ICal-путь будет стабилен. Capability-методы в ChannelAdapter уже готовы к этому.
- **Полноценная CI с прогоном тестов** — отдельным мелким PR. Сейчас в Dockerfile `./mvnw package -DskipTests`, т.е. тесты не гоняются ни в CI, ни при деплое.
- **Пилот на Суточно** — в активной фазе: импорт фида Суточно подключён на staging для Садовой (property_id=5), жду ответа саппорта по тому, как отдать нашу ссылку обратно в Суточно (у них скрыта опция ручного импорта iCal, когда объект синхронизирован через channel manager).

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
5. Если про миграции — обязательно V-номер больше последнего (сейчас V19), не удалять поля из существующих.
6. Если про UI — следовать существующей стилистике (dark theme + CSS vars из core.css).
7. Если делаешь блок — завершить INTEGRATION_<N>.md в patches/ с инструкциями по применению.
8. **Git-операции — предлагай, но не выполняй сам.**
    - Правь файлы напрямую.
    - Для `git add` / `git commit` / `git push` — предлагай команду, жди подтверждения.
    - Не трогай main-ветку никогда.
    - На feat-ветках коммитить можно, но только с чётким сообщением и после одобрения.
9. После пуша — помнить про CSRF-грабли (hard reload / incognito в staging).
10. При падении деплоя — сначала проверить баг-1 (контейнеру > 2 мин? образ пересобрался?), только потом искать баги в коде.