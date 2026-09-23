# Патчи для обезличивания на входе

## 1. PricingEngine.java — 2 места

### Оба метода `syncRcBookings` и `syncRcBookingsFromNode`

**Импорт** в начало файла:
```java
import ru.rentoptima.util.PdAnonymizer;
```

**Найди** (в обоих методах, одинаковый блок):

```java
                String guest = ev.path("client").path("fio").asText(null);
                if (guest != null) guest = guest.trim();
                if (guest != null && guest.isEmpty()) guest = null;
                String phone = ev.path("client").path("phone").asText(null);
                double amount = ev.path("amount").asDouble(0);
```

**Замени на:**

```java
                String guestRaw = ev.path("client").path("fio").asText(null);
                String guest = PdAnonymizer.toInitial(guestRaw);
                String phone = PdAnonymizer.stripPhone(
                        ev.path("client").path("phone").asText(null));
                double amount = ev.path("amount").asDouble(0);
```

Проверка на "Ручное закрытие" — оставь как было (внутри блока `if (guest == null || guest.isBlank()) guest = "Ручное закрытие RC";`). Всё должно быть под `guest = PdAnonymizer.toInitial(...)`.

---

## 2. WebhookService.java — строки 147-150

**Импорт:**
```java
import ru.rentoptima.util.PdAnonymizer;
```

**Найди:**
```java
            String fio = getText(client, "fio");
            if (fio != null) booking.setGuestName(fio);
            String phone = getText(client, "phone");
            if (phone != null) booking.setGuestPhone(phone);
```

**Замени на:**
```java
            String fio = getText(client, "fio");
            String initial = PdAnonymizer.toInitial(fio);
            if (initial != null) booking.setGuestName(initial);
            booking.setGuestPhone(PdAnonymizer.stripPhone(getText(client, "phone")));
```

---

## 3. ImportService.java — строка 91 (и 81)

**Импорт:**
```java
import ru.rentoptima.util.PdAnonymizer;
```

**Найди:**
```java
                    b.setGuestName(guestName);
```

**Замени на:**
```java
                    b.setGuestName(PdAnonymizer.toInitial(guestName));
```

**Плюс — поиск дубликатов** (строка 81 использовала `guestName`).
Ищем существующие брони по инициалам:

**Найди:**
```java
                            propertyId, guestName, checkIn, checkOut)) {
```

**Замени на:**
```java
                            propertyId, PdAnonymizer.toInitial(guestName), checkIn, checkOut)) {
```

**Плюс телефон** — найди установку `b.setGuestPhone(...)` в ImportService (если есть) и замени значение на `null` или удали строку. Скинь если не найдёшь — уточню.

---

## 4. FeedbackController.java

**Импорт:**
```java
import ru.rentoptima.util.PdAnonymizer;
```

**Найди:**
```java
        response.setGuestName(request.guestName());
```

**Замени на:**
```java
        response.setGuestName(PdAnonymizer.toInitial(request.guestName()));
```

Гость сам вводит своё имя в форму — обрезаем до инициала.

---

## 5. RcSyncService.java

Проверить строку где создаётся Booking и устанавливается guest:

```bash
grep -n "setGuestName\|setGuestPhone" src/main/java/ru/rentoptima/service/RcSyncService.java
```

Если там `b.setGuestName(rcB.guestName())` — уже правильно, т.к. `rcB` уже приходит из `PricingEngine` с обезличенными данными.
Если там что-то ещё — скинь, поправим.

---

## 6. Проверка логов

Запусти на всякий случай:
```bash
grep -rn 'log\..*guest\|log\..*fio\|log\..*phone' src/main/java/ru/rentoptima/
```

Скинь вывод — если увидим `log.info("... {}", guest)` где guest — полное имя, поправим на маску. Логика: даже если у нас в БД инициалы, в логах могут быть полные ФИО (например RcSyncService логгирует до передачи в анонимизатор).

---

## 7. WebhookController.java

Проверь как принимает вебхуки:
```bash
grep -n "setGuestName\|fio\|phone" src/main/java/ru/rentoptima/controller/WebhookController.java
```

Скинь вывод. Если данные читаются напрямую там (а не в WebhookService), нужно ещё один патч.

---

## После деплоя

1. Проверь БД:
```bash
docker compose exec db psql -U rentoptima -c "
SELECT COUNT(*) as with_full_name FROM bookings 
WHERE LENGTH(guest_name) > 3 AND guest_name NOT LIKE 'Ручное%';
SELECT COUNT(*) as with_phone FROM bookings WHERE guest_phone IS NOT NULL;
"
```

Оба должны быть 0.

2. Проверь свежие брони после ближайшего sync — должны приходить как «Р.», «I.», без телефонов.

---

**Коммит:**
```
feat: PD anonymization at entry points (RC events, webhooks, XLS import, feedback form)
```
