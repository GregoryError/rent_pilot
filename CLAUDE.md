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
- Flyway (миграции V1..V18+)
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

### Agent-сессии не коммитят напрямую

Agent готовит код и инструкции (блоки с INTEGRATION.md). Применяет и коммитит разработчик руками. Это даёт точку ревью перед каждым коммитом.

---

## Приоритеты спринта (блоки 4.4 - 4.8)

### Что уже сделано (feat/channels-mvp)

- **Block 1** (V16): unit_types, channels, calendar_blocks, расширение bookings
- **Block 2** (V17): Channel abstraction + iCal (parser/writer/fetcher/adapter), AvailabilityService, public /ical/{secret}.ics
- **Block 3** (V18, частично): UI /settings/channels, SSRF-защита в HttpICalFeedFetcher, scheduler, backfill legacy RC-броней
- Шахматка v1 (визуализация занятости)
- UI-обновление: светлая тема, logo fragment, floating AI chat, POST logout

### Что в работе / приоритет

**Блок 4.4 — Ручная бронь через UI (P0)**
- Форма создания CalendarBlock с type=MANUAL_BOOKING
- Валидация пересечений с существующими бронями
- Автоэкспорт в iCal всех активных каналов unit_type (anti-echo)

**Блок 4.5 — Детектор конфликтов + Telegram-алерты (P0)**
- Scheduled task раз в N минут — поиск пересечений между Booking и CalendarBlock одного unit_type
- Telegram-бот с токеном в system_settings
- Уведомление tenant-админу с ссылкой на /calendar/grid

**Блок 4.6 — AvitoClient + AvitoChannel (P0)**
- OAuth 2.0 с Avito API (2025)
- Pull бронирований через API (не iCal)
- Push доступности через API
- Обработка 409 Conflict → Telegram-алерт

**Блок 4.7 — Пилот на Садовой (P0)**
- Регистрация tenant в staging для Садовой (параллельно с prod/RC)
- Отключить Twil в RC-кабинете, подключить через iCal в OptiRent
- Замер задержки распространения занятости между площадками
- Логировать все пересечения дат

**Блок 4.8 — Тесты (P1, тех-долг)**
- src/test пустой — критический пробел
- Приоритет: ICalParser (RFC 5545 + реальные фикстуры Sutochno/Ostrovok/Twil)
- AvailabilityService.mergeToPeriods — краевые случаи с unit_count > 1
- ICalChannelAdapter — идемпотентность, reconcile в окне

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

Скрипт `/opt/rentoptima-*/scripts/deploy.sh` сравнивает `CURRENT_SHA` и `LATEST_SHA` **до** `git fetch`, из-за чего иногда пропускает деплой изменений. Workaround — `git fetch origin branch` перед сравнением (уже исправлено в staging и partner версиях, в prod ещё старый).

**Баг 2 — тесты не прогоняются в CI**

Dockerfile собирает `./mvnw package -DskipTests`. Нужно: либо прогонять тесты в GitHub Actions отдельным шагом перед SSH-деплоем, либо убрать `-DskipTests` из Dockerfile (замедлит сборку, но ловит регрессии).

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

1. Прочитать этот файл (ты уже это сделал если видишь текст)
2. Проверить какая активная ветка: обычно работаем на feat/channels-mvp или фичевой ветке от неё
3. Если задача про Property или bookings — помнить про двойной маппинг tenant_id
4. Если про шаблоны — pipe-syntax или th:classappend, не плюсы
5. Если про миграции — обязательно V-номер больше последнего, не удалять поля из существующих
6. Если про UI — следовать существующей стилистике (dark theme + CSS vars из core.css)
7. Если делаешь блок — завершить INTEGRATION_<N>.md в patches/ с инструкциями по применению
8. **Не коммитить самому** — патчи, разработчик применяет и коммитит руками
