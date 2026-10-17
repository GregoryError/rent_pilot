# iCal-экспорт: одна запись — один VEVENT

Ветка: `feat/channels-mvp`. Миграций нет.

## Что было

`ICalWriter` сам ничего не склеивал. Склейка жила слоем ниже: `ICalFeedController` брал
`AvailabilityService.busyPeriods()` — список распроданных ночей, схлопнутый в непрерывные
интервалы (`mergeToPeriods`), с UID от даты начала (`ut3-2026-12-24`). 86 блокировок,
идущих встык, превращались в один VEVENT.

## Что стало

| Файл | Что изменилось |
|---|---|
| `service/AvailabilityService.java` | `exportEvents()` / `buildExportEvents()`: запись в запись, без склейки |
| `channel/ical/ICalWriter.java` | UID пишется как есть, домен больше не дописывается |
| `controller/ICalFeedController.java` | берёт `exportEvents()` |
| `test/.../ICalExportEventsTest.java` | 3 соседние блокировки → 3 VEVENT; UID `aaa`/`bbb`; 86 встык → 86; anti-echo; ручная пара; дубль внешнего UID |

- **UID блокировки** — её `external_uid`; если его нет — `block-<id>@optirent.ru`.
- **UID брони** — `booking-<id>@optirent.ru`.
- **Ручная бронь** (блокировка + бронь с теми же датами) — одно событие, а не два.
- **Anti-echo** теперь распространяется и на брони: бронь канала не отдаётся в его же фид.
- **Одинаковый внешний UID от двух каналов** — второй получает `block-<id>@…`, иначе площадка
  схлопнула бы два события в одно.

## Чего это не меняет

- **Набор закрытых ночей прежний.** `mergeToPeriods` склеивал только ночи, идущие подряд, — через
  свободную ночь он не перешагивал. Если фид отдавал один интервал 24.12.2026 — 08.10.2027, значит
  в базе заняты все ночи этого периода. Новый фид отдаст 86 событий, но закроют они те же ночи.
- **Мини-отель** (`unit_count > 1`) по-прежнему отдаёт склеенные распроданные интервалы с UID от
  даты: там отдельная запись не означает «продано».
- **UID у всех событий сменились один раз** (был домен `rentoptima.ru` и UID от даты) — площадки
  увидят это как замену событий при следующем опросе.

## Проверка после деплоя

```bash
curl -s https://staging.optirent.ru/ical/<secret>.ics -u admin:… | grep -c BEGIN:VEVENT
curl -s https://staging.optirent.ru/ical/<secret>.ics -u admin:… | grep ^UID | head
```

Есть ли свободные ночи внутри периода (если запрос ничего не вернул — зазоров нет):

```sql
SELECT a.to_date AS free_from, MIN(b.from_date) AS free_to
  FROM calendar_blocks a
  JOIN calendar_blocks b ON b.unit_type_id = a.unit_type_id AND b.from_date > a.to_date
 WHERE a.unit_type_id = 3 AND a.from_date >= DATE '2026-12-01'
   AND NOT EXISTS (SELECT 1 FROM calendar_blocks c
                    WHERE c.unit_type_id = a.unit_type_id
                      AND c.from_date <= a.to_date AND c.to_date > a.to_date)
 GROUP BY a.to_date ORDER BY 1;
```
