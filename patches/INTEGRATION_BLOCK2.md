# Блок 2 спринта: Channel abstraction + iCal (внедрение работы агента)

## Что делаем

Внедряем проделанную работу другого агента: интерфейс `Channel`, реализации (RC, Manual, iCal), парсер/писатель iCal, публичный feed endpoint, AvailabilityService.

Плюс V17 миграция — расширение `channels` и `calendar_blocks` под ключи идемпотентности и export_secret.

## Новые файлы (из архива, разложены по правильным пакетам)

**`src/main/java/ru/rentoptima/channel/`:**
- `ChannelAdapter.java` — интерфейс
- `ChannelAdapterRegistry.java` — Spring auto-registry
- `ChannelContext.java`, `ChannelSyncResult.java`, `ChannelSyncException.java`
- `ManualChannelAdapter.java`
- `RcChannelAdapter.java` — тонкая обёртка над PricingEngine.triggerRcSyncWithPrices

**`src/main/java/ru/rentoptima/channel/ical/`:**
- `ICalEvent.java` — record
- `ICalParser.java` — RFC 5545 подмножество без ical4j
- `ICalWriter.java` — byte-safe folding для UTF-8
- `ICalFeedFetcher.java` — интерфейс
- `HttpICalFeedFetcher.java` — реализация с таймаутами и MAX_BYTES
- `ICalChannelAdapter.java` — reconcile с идемпотентностью

**`src/main/java/ru/rentoptima/controller/`:**
- `ICalFeedController.java` — публичный GET /ical/{secret}.ics

**`src/main/java/ru/rentoptima/service/`:**
- `ChannelSyncService.java` — оркестратор
- `AvailabilityService.java` — occupancy + busyPeriods с anti-echo

**`src/main/resources/db/migration/`:**
- `V17__channel_idempotency_and_secrets.sql`

## Обновлённые файлы (перезаписывают существующие в feat/channels-mvp)

- `Channel.java` — добавлены поля exportSecret, lastSyncImported, lastSyncRemoved
- `CalendarBlock.java` — добавлены channelId, externalUid, BlockType.CHANNEL_SYNC
- `Booking.java` — добавлены channelId, unitTypeId, externalId
- `ChannelRepository.java` — findByExportSecret, findByTenantIdAndActiveTrueAndSyncEnabledTrue
- `CalendarBlockRepository.java` — findByChannelIdAndExternalUid, findByChannelInRange, findByUnitTypesInRange

## Требует РУЧНОГО патча

### 1. BookingRepository.java — добавь метод

Открой `src/main/java/ru/rentoptima/repository/BookingRepository.java` и добавь в конце (перед последней `}`):

```java
    /** Активные брони по нескольким unit_type в окне [from, to). Для AvailabilityService. */
    @Query("""
        SELECT b FROM Booking b
        WHERE b.unitTypeId IN :unitTypeIds
          AND b.status = 'BOOKED'
          AND b.checkOut > :from
          AND b.checkIn < :to
        ORDER BY b.checkIn
    """)
    List<Booking> findActiveByUnitTypesInRange(java.util.List<Long> unitTypeIds,
                                                LocalDate from, LocalDate to);
```

### 2. SecurityConfig.java — разрешить публичный iCal endpoint

Открой `src/main/java/ru/rentoptima/config/SecurityConfig.java`. Найди список permitAll:

```java
                        .requestMatchers(
                                "/login",
                                "/register",
                                "/legal/**",
                                ...
                        ).permitAll()
```

Добавь строку `"/ical/**",`. Финал должен выглядеть так:

```java
                        .requestMatchers(
                                "/login",
                                "/register",
                                "/legal/**",
                                "/ical/**",
                                "/css/**",
                                ...
                        ).permitAll()
```

Плюс убедись что `/ical/**` попадает в `.csrf(csrf -> csrf.ignoringRequestMatchers(...))` — если там уже есть `/api/**`, `/webhooks/**`, добавь туда:

```java
                        .csrf(csrf -> csrf
                                .ignoringRequestMatchers(
                                        new AntPathRequestMatcher("/api/**"),
                                        new AntPathRequestMatcher("/webhooks/**"),
                                        new AntPathRequestMatcher("/ical/**"),
                                        ...
```

(На самом деле для GET-запросов CSRF не проверяется, но для чистоты добавим — вдруг кто-то в будущем добавит POST для webhook-подобной регистрации iCal.)

## Что НЕ включено (осталось от работы агента)

Агент упомянул страницу `/settings/channels` с контроллером и шаблоном — я её не нашёл в файлах проекта. Сделаем отдельно — это UI, а сейчас важнее чтобы backend собирался.

Также агент упомянул `INTEGRATION_BLOCK2.md` — тоже не нашёл. Ключевые архитектурные решения описаны в javadoc самого кода агента, там всё видно.

## Проверка после деплоя

```bash
# 1. Автодеплой должен подхватить push автоматически
# Смотри GitHub Actions

# 2. Миграция V17
docker exec rentoptima-staging-db psql -U rentoptima -d rentoptima_staging \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"

# Ожидаем V17 success=t

# 3. Проверить новые поля
docker exec rentoptima-staging-db psql -U rentoptima -d rentoptima_staging \
  -c "\d channels" | grep -E "export_secret|last_sync_imported|last_sync_removed"

docker exec rentoptima-staging-db psql -U rentoptima -d rentoptima_staging \
  -c "\d calendar_blocks" | grep -E "channel_id|external_uid"

docker exec rentoptima-staging-db psql -U rentoptima -d rentoptima_staging \
  -c "\d bookings" | grep -E "channel_id|unit_type_id|external_id"

# 4. Проверить что iCal endpoint отвечает (без секрета - 404)
curl -I https://admin:PASS@staging.optirent.ru/ical/nonexistent.ics
# Ожидаем HTTP/2 404

# 5. Логи приложения
docker logs rentoptima-staging-app --tail 30 | grep -E "Channel adapters registered|Started"
# Ожидаем "Channel adapters registered: [RC, ICAL, MANUAL]"
```

## Порядок применения

**На локальной машине (Mac):**

```bash
git checkout feat/channels-mvp

# 1. Распакуй архив в корень проекта
tar -xzf blk2-agent-integration.tar.gz

# 2. Файлы разложились по правильным путям. Проверь:
find src/main/java/ru/rentoptima/channel -type f
find src/main/resources/db/migration/V17* -type f

# 3. Примени два ручных патча:
#    - BookingRepository.java (добавь метод findActiveByUnitTypesInRange)
#    - SecurityConfig.java (добавь /ical/** в permitAll + CSRF ignore)

# 4. Коммит
git add -A
git commit -m "feat(channels): iCal adapter + AvailabilityService + V17 idempotency

- ChannelAdapter interface with pull/supportsPull/supportsIcalExport
- ChannelAdapterRegistry auto-collects Spring beans by type
- ICalParser (RFC 5545 subset, no ical4j)
- ICalWriter with byte-safe UTF-8 folding
- HttpICalFeedFetcher with timeouts, 5MB cap, webcal:// support
- ICalChannelAdapter: idempotent import + reconcile within window
- AvailabilityService: occupancy considering unit_count, anti-echo export
- Public GET /ical/{secret}.ics with 192-bit secret
- V17: export_secret, last_sync_imported/removed, (channel_id, external_uid) unique
- Booking: channelId, unitTypeId, externalId fields
- CalendarBlock: channelId, externalUid, BlockType.CHANNEL_SYNC

Code by another agent, reviewed and integrated with staging infrastructure."

git push
```

**Автодеплой запустится сам.** Через 3-4 минуты staging обновлён.

## Что осталось из спринта

Согласно `partners/sprint-1.md`, после Блока 2 нужно:
- **Ручная бронь через UI** (форма создания CalendarBlock)
- **Детектор конфликтов** + Telegram-алерты
- **AvitoClient + AvitoChannel**
- **Планировщик** для регулярного pull всех каналов
- **Backfill legacy RC-броней** (проставить channel_id, unit_type_id, external_id)
- **UI `/settings/channels`** для управления каналами
- **Пилот на Садовой** — отключить Твил в RC, подключить через iCal

**Тестов пока нет.** Агент их не написал (был остановлен раньше). Приоритетно нужно покрыть парсер iCal и AvailabilityService.merge — там больше всего скрытых кейсов.
