# Расширение overrides — 4 типа

## 1. OverrideResolver.java — уже в архиве, полная замена

## 2. AiChatService.java — обновить промпт

Найди в методе `buildSystemPrompt` блок `Возможные типы:` и замени всё что относится к типам на:

```
Возможные типы:
- price_multiplier — умножить цены на factor.
  params: {"factor":1.10,"from":"2026-10-01","to":"2026-10-31"}
  factor от 0.5 до 3.0 (например 1.10 = +10%%, 0.90 = -10%%)

- min_stay_override — задать минимальный срок для диапазона.
  params: {"value":3,"from":"2026-10-01","to":"2026-10-31"}
  value от 1 до 14 ночей

- close_dates — закрыть диапазон дат для новых бронирований.
  params: {"from":"2026-10-15","to":"2026-10-17"}

- open_ahead_days — сколько дней вперёд открывать цены.
  params: {"days":45}
  days от 7 до 365

- floor_ceil — установить границы цены.
  params: {"floor":2500,"ceil":6000}
  floor > 0, ceil > floor, оба меньше 100000
```

## 3. ChatController.java — расширить isValidType

Найди:
```java
    private boolean isValidType(String type) {
        return List.of("price_multiplier").contains(type);
    }
```

Замени на:
```java
    private boolean isValidType(String type) {
        return List.of(
                "price_multiplier",
                "min_stay_override",
                "close_dates",
                "open_ahead_days",
                "floor_ceil"
        ).contains(type);
    }
```

## 4. PricingEngine.java — применить все override

### a) В начале `runForProperty` — override для open_ahead_days

Найди:
```java
        int openAheadDays = settings.getIntValue(
                tenantId,
                "open_ahead_days",
                30
        );
```

Замени на:
```java
        int openAheadDays = settings.getIntValue(tenantId, "open_ahead_days", 30);
        Integer openAheadOverride = overrideResolver.getActiveOpenAheadDays(
                tenantId, property.getId());
        if (openAheadOverride != null) {
            openAheadDays = openAheadOverride;
            log.info("Autopilot [{}]: open_ahead_days override to {} for {}",
                    mode, openAheadDays, property.getName());
        }
```

### b) closedDates — добавить override-даты

Найди строку где инициализируется calendarState (после `loadCalendarState`):

```java
        CalendarState calendarState;
        try {
            calendarState = loadCalendarState(...);
```

После блока `try/catch` (после присваивания calendarState) добавь:

```java
        // Merge override close_dates with RC-closed dates
        Set<LocalDate> closedOverride = overrideResolver.getClosedDates(
                tenantId, property.getId());
        if (!closedOverride.isEmpty()) {
            calendarState.closedDates().addAll(closedOverride);
            log.info("Autopilot [{}]: {} dates closed by override for {}",
                    mode, closedOverride.size(), property.getName());
        }
```

### c) floor_ceil — переопределить границы

Найди в `runForProperty`:
```java
            BigDecimal finalPrice = applyAiAdjustment(
                    rec, adjustments, tenantId);
```

Прямо после этого добавь применение floor_ceil override:

```java
            int[] fcOverride = overrideResolver.getActiveFloorCeil(tenantId, property.getId());
            if (fcOverride != null) {
                int fp = finalPrice.intValue();
                fp = Math.max(fcOverride[0], Math.min(fcOverride[1], fp));
                finalPrice = BigDecimal.valueOf(fp);
            }
```

### d) min_stay_override — переопределить срок

Найди блок где создаётся `SpecialPrice`:

```java
            items.add(
                    new RealtyCalendarClient.SpecialPrice(
                            rec.date(),
                            finalPrice,
                            minStayInt
                    )
            );
```

Прямо перед этим блоком (там где вычисляется `minStayInt`):

```java
            int priceInt = finalPrice.intValue();
            int minStayInt = rec.recommendedMinStay();

            // min_stay override
            Integer overrideStay = overrideResolver.getActiveMinStay(
                    tenantId, property.getId(), rec.date());
            if (overrideStay != null) {
                minStayInt = overrideStay;
            }
```

## 5. chat/index.html — describeAction для новых типов

Найди `function describeAction(action)` и замени тело функции на:

```javascript
function describeAction(action) {
    const p = action.params || {};
    switch (action.type) {
        case 'price_multiplier': {
            const pct = Math.round((p.factor - 1) * 100);
            return `Умножить цены на ${p.factor} (${pct > 0 ? '+' : ''}${pct}%) с ${p.from} по ${p.to}`;
        }
        case 'min_stay_override':
            return `Минимальный срок ${p.value} ночей с ${p.from} по ${p.to}`;
        case 'close_dates':
            return `Закрыть даты с ${p.from} по ${p.to} для бронирования`;
        case 'open_ahead_days':
            return `Открывать цены на ${p.days} дней вперёд`;
        case 'floor_ceil':
            return `Границы цены: от ${p.floor}₽ до ${p.ceil}₽`;
        default:
            return action.type;
    }
}
```

## Проверка после деплоя

1. В /chat напиши: «Закрой даты с 20 по 22 октября» — должна прийти карточка close_dates
2. Напиши: «Открывай цены только на 30 дней вперёд» — open_ahead_days
3. Напиши: «Поставь минимальный срок 3 ночи в октябре» — min_stay_override
4. Напиши: «Ограничь цены от 3000 до 5500» — floor_ceil
5. Проверь БД:

```bash
docker compose exec db psql -U rentoptima -c "SELECT override_type, params_json, description FROM manual_overrides WHERE active = TRUE;"
```

## Коммит:
```
feat: extend manual overrides — min_stay, close_dates, open_ahead_days, floor_ceil
```
