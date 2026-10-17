# Блок 1 спринта: Модель данных для channels

## Что делаем

Первый шаг архитектуры — вводим три новые сущности:
- **UnitType** — категории номеров внутри property (для мини-отелей + гибкой модели)
- **Channel** — подключённый канал синхронизации (RC, Avito, iCal, Manual)
- **CalendarBlock** — блокировки дат (ручные брони, ремонт, hold)

Плюс расширяем `Booking` полями `channel_id`, `unit_type_id`, `external_id`.

## Файлы из архива

Распакуй, файлы уже в правильных путях:
- `src/main/resources/db/migration/V16__channels_foundation.sql`
- `src/main/java/ru/rentoptima/entity/UnitType.java`
- `src/main/java/ru/rentoptima/entity/Channel.java`
- `src/main/java/ru/rentoptima/entity/CalendarBlock.java`
- `src/main/java/ru/rentoptima/repository/UnitTypeRepository.java`
- `src/main/java/ru/rentoptima/repository/ChannelRepository.java`
- `src/main/java/ru/rentoptima/repository/CalendarBlockRepository.java`

## Патч в Booking.java

Открой `src/main/java/ru/rentoptima/entity/Booking.java`. После поля:

```java
    @Column(nullable = false)
    private String status = "BOOKED";
```

Добавь:

```java
    /** ID канала откуда пришла бронь. Nullable для legacy бронирований до spring-1. */
    @Column(name = "channel_id")
    private Long channelId;

    /** ID unit_type — обязателен после миграции V16. */
    @Column(name = "unit_type_id")
    private Long unitTypeId;

    /**
     * External ID брони в исходной системе:
     * - RC: booking_id из RC (для legacy — тот же что в rc_booking_id)
     * - Avito: booking_id из Avito API
     * - iCal: UID из VEVENT
     * - Manual: null
     */
    @Column(name = "external_id")
    private String externalId;
```

## Проверка после деплоя на staging

```bash
cd /opt/rentoptima-staging
git pull
docker compose up --build -d
```

Дождись сборки и старта. Проверь миграцию:

```bash
docker exec rentoptima-staging-db psql -U rentoptima -d rentoptima_staging -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
```

Должна появиться V16 с success=t.

Проверь новые таблицы:

```bash
docker exec rentoptima-staging-db psql -U rentoptima -d rentoptima_staging -c "\d unit_types"
docker exec rentoptima-staging-db psql -U rentoptima -d rentoptima_staging -c "\d channels"
docker exec rentoptima-staging-db psql -U rentoptima -d rentoptima_staging -c "\d calendar_blocks"
docker exec rentoptima-staging-db psql -U rentoptima -d rentoptima_staging -c "\d bookings" | tail -15
```

Плюс проверь что зарегистрированный на staging пользователь получил дефолтный unit_type. Если у тебя ещё нет объектов на staging — миграция ничего не создаст, это ок.

## Коммит

```bash
git checkout feat/channels-mvp
git add -A
git commit -m "feat(channels): V16 migration + UnitType/Channel/CalendarBlock entities"
git push
```

## Что дальше — Блок 2

Следующий шаг — интерфейс `Channel` (java) и обёртка `RcChannel` вокруг существующего RC-кода. Это позволит потом добавлять новые каналы (Avito, iCal, Manual) единообразно, не меняя pricing engine и сервисы синхронизации.
