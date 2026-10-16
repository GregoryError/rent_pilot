# INTEGRATION_LEGACY_LINKS — розданные ссылки старого prod после переключения

На прежнем prod (ветка legacy-rc, база `rentoptima`, контейнер `rentoptima-db-1`) розданы две
ссылки «Садовой 5 кв. 38»: анкета отзывов `/feedback/<код>` (QR в квартире) и страница горничной
`/housekeeper/<код>` (у неё в закладках). После переключения optirent.ru на новую версию
(`INTEGRATION_PROD_SWITCH.md`) они должны открываться без изменений. Заодно переносятся отзывы
объекта.

Здесь же: горизонт страницы горничной (V41) и проверка, что она не видит открытые хозяином даты.

---

## 1. Как устроены ссылки (legacy-rc и main)

Разобрано по `origin/main` @ `79eee25` (из него создаётся legacy-rc) и `feat/channels-mvp`.

### /feedback/{код}

- Код — `properties.feedback_code` (`VARCHAR(50) NOT NULL UNIQUE`, V1), колонка объекта.
  Отдельной таблицы ссылок нет; код привязан к объекту целиком.
- `GET /feedback/{код}` → `findByFeedbackCode` → анкета с новым `sessionId` (UUID).
  `POST /api/feedback/submit` по `propertyCode` + `sessionId` создаёт/обновляет строку
  `feedback_responses`.
- `feedback_responses`: `property_id`, `booking_id` (NULL допустим), `session_id` UNIQUE,
  `guest_name` (только инициал), `guest_phone` (V8 обнулила, код не пишет), `completed`,
  `show_to_housekeeper`, `created_at`, `updated_at`, `agreed_to_consent_at` (V15).
- `feedback_answers`: строка на вопрос — `response_id`, `question_id`, `numeric_value` /
  `text_value`, `answered_at`. Вопросы — `feedback_questions` хозяина (`tenant_id`,
  `property_id` NULL — общий); при сохранении ответы раскладываются по тексту вопроса.
- **Различие:** в main есть `feedback_responses.source` (V33): NULL — анкета, `CARD` — QR `/f/`
  с карточки. Перенесённые отзывы получают `MIGRATED`.

### /housekeeper/{код}

- Код — `properties.housekeeper_code` (`VARCHAR(50) NOT NULL UNIQUE`, V7), PIN —
  `properties.housekeeper_pin_hash` (BCrypt, NULL — не задан).
- Вход `POST /housekeeper/{код}/login`: `BCrypt.matches(pin, hash)` → cookie `hk_auth` на
  путь `/housekeeper/{код}`, HttpOnly, 7 дней, значение `md5(pin_hash + ":" + properties.id)`.
- **Следствие:** в новой базе у объекта другой id, поэтому сохранённый вход горничной не
  подойдёт — ссылка откроется и **один раз попросит PIN** (тот же). Решено так и оставить.
- **Различие:** в legacy-rc график — только брони, без верхней границы; в main — брони и
  блокировки площадок, горизонт — настройка объекта (раздел 4).

---

## 2. Перенос: `scripts/legacy-links/`

| Файл | База | Что делает |
|---|---|---|
| `export.sql` | старая (`rentoptima`) | только чтение: объект, отзывы, ответы, вопросы → CSV |
| `import.sql` | новая (`rentoptima_staging`) | одна транзакция: коды, PIN, вопросы, отзывы, ответы, проверки, отчёт |

Что переносится:

- `feedback_code`, `housekeeper_code`, `housekeeper_pin_hash` старого объекта **заменяют**
  коды объекта в новой базе. Ссылки с прежними кодами нового объекта (если их раздавали со
  staging) перестанут работать. Коды `/q/` и `/f/` карточек (`widget_qr_links`) не трогаются —
  скрипт проверяет, что их число не изменилось.
- Отзывы объекта: `property_id` — новый объект, `booking_id = NULL`, `source = 'MIGRATED'`,
  `guest_phone = NULL`, даты (`created_at`, `updated_at`, `agreed_to_consent_at`,
  `answered_at`) — как были.
- Вопросы: сопоставляются по тексту (без регистра и крайних пробелов) и типу среди вопросов
  хозяина нового объекта — общих и этого объекта; при нескольких совпадениях — активный, затем
  общий. Несопоставленные создаются у объекта **неактивными**: в анкете их нет, ответы на них
  видны хозяину и горничной с подписью вопроса (подписи теперь берутся и у выключенных
  вопросов — `FeedbackQuestionRepository.findByTenantId`).

Проверки (любая не прошла — `ROLLBACK`, код выхода 3, база не изменена):

| До | После |
|---|---|
| в выгрузке ровно один объект; файлы совпадают с `counts.csv`; у каждого ответа есть отзыв и вопрос | перенесено отзывов = выгружено − пропущено; стало = было + перенесено |
| объект `new_property_id` есть и называется «Садовая…» | то же для ответов |
| `feedback_code` / `housekeeper_code` не заняты **другим** объектом (с id и именем в ошибке) | коды объекта записались |
| | число кодов карточек `/q/` `/f/` хозяина не изменилось |

Повторный запуск безопасен: отзывы с уже существующим `session_id` пропускаются, созданные в
прошлый раз вопросы находятся. Это позволяет догрузить отзывы, оставленные между выгрузкой и
переключением nginx.

### Проверено локально

Postgres 16 в docker, две пустые базы: старая — миграции V1–V15 из `origin/main`, новая —
V1–V41. Данные: «Садовая» с тремя отзывами и семью ответами (в т. ч. на выключенный вопрос,
которого нет в новой базе), второй объект с отзывом, в новой базе — вопрос с другим регистром
и пробелами и одноимённый вопрос другого типа.

- выгрузка: второй объект не попал; `dry_run` — отчёт верный, база не изменилась;
- загрузка: сопоставлено 6, создан 1; 3 отзыва и 7 ответов на правильных вопросах, даты и
  инициалы сохранены, второй объект не тронут;
- повторный запуск: сопоставлено 7, создано 0, перенесено 0, пропущено 3;
- ошибки: объект «Ленина» вместо «Садовой», несуществующий id, нет параметра, занятые
  `feedback_code` и `housekeeper_code`, обрезанный `answers.csv` — везде ROLLBACK, код 3;
- репетиция: `pg_dump` новой базы → `pg_restore` в пустую базу → загрузка — то же.

**На настоящих базах не запускалось.**

### Порядок на сервере

Пути и имена — как в `INTEGRATION_PROD_SWITCH.md`. Скрипты лежат в
`/opt/rentoptima-staging/scripts/legacy-links/` (после выкладки этого коммита).

**0. Узнать id объекта в новой базе:**

```bash
docker exec rentoptima-staging-db psql -U rentoptima -d rentoptima_staging \
  -c "SELECT id, name, feedback_code, housekeeper_code FROM properties WHERE name ILIKE 'Садовая%'"
```

**1. Выгрузка из старой базы** (только чтение, можно днём):

```bash
docker exec rentoptima-db-1 mkdir -p /tmp/legacy-links
docker exec -i -w /tmp/legacy-links rentoptima-db-1 \
  psql -U rentoptima -d rentoptima < /opt/rentoptima-staging/scripts/legacy-links/export.sql
# если «найдено объектов: 2» — добавить -v old_property_id=<id> (id из подсказки в ошибке)

mkdir -p /root/legacy-links
docker cp rentoptima-db-1:/tmp/legacy-links/. /root/legacy-links/
ls -l /root/legacy-links    # property, questions, responses, answers, counts (.csv)
```

Файлы содержат хеш PIN и отзывы гостей — хранить в `/root`, после переноса удалить.

**2. Репетиция на копии новой базы** — тот же скрипт, база-копия:

```bash
docker exec rentoptima-staging-db sh -c \
  'pg_dump -U rentoptima -Fc rentoptima_staging > /tmp/rehearsal.dump \
   && createdb -U rentoptima legacy_rehearsal \
   && pg_restore -U rentoptima -d legacy_rehearsal --no-owner /tmp/rehearsal.dump'
docker exec rentoptima-staging-db mkdir -p /tmp/legacy-links
docker cp /root/legacy-links/. rentoptima-staging-db:/tmp/legacy-links/
docker exec -i -w /tmp/legacy-links rentoptima-staging-db \
  psql -U rentoptima -d legacy_rehearsal -v new_property_id=<id> \
  < /opt/rentoptima-staging/scripts/legacy-links/import.sql
# отчёт: «Вопросы: сопоставлено N, создано неактивными M», «Отзывы: перенесено …», «После: …»
docker exec rentoptima-staging-db sh -c 'dropdb -U rentoptima legacy_rehearsal; rm /tmp/rehearsal.dump'
```

**3. Боевой запуск** — после выкладки main с `APP_BASE_URL` (шаг 9 переключения) и **до**
переключения nginx (шаг 10). Сначала `-v dry_run=1`, потом без него:

```bash
docker exec -i -w /tmp/legacy-links rentoptima-staging-db \
  psql -U rentoptima -d rentoptima_staging -v new_property_id=<id> -v dry_run=1 \
  < /opt/rentoptima-staging/scripts/legacy-links/import.sql
docker exec -i -w /tmp/legacy-links rentoptima-staging-db \
  psql -U rentoptima -d rentoptima_staging -v new_property_id=<id> \
  < /opt/rentoptima-staging/scripts/legacy-links/import.sql
```

**4. После переключения nginx** — повторить шаг 1 и шаг 3 (без `dry_run`): догрузятся отзывы,
оставленные на старом prod между выгрузкой и переключением; уже перенесённые пропустятся.

**5. Проверка:**

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://optirent.ru/feedback/<старый feedback_code>      # 200
curl -s -o /dev/null -w '%{http_code}\n' https://optirent.ru/housekeeper/<старый housekeeper_code> # 200, форма PIN
```

Горничная вводит прежний PIN; в кабинете «Отзывы» — перенесённые отзывы со старыми датами.
Удалить `/root/legacy-links` и `/tmp/legacy-links` в обоих контейнерах.

**Откат** (если перенос надо отменить, nginx ещё не переключён):

```sql
-- коды «до» — в отчёте скрипта, строка «Коды до: …»
UPDATE properties SET feedback_code = '<до>', housekeeper_code = '<до>', housekeeper_pin_hash = NULL
WHERE id = <id>;
DELETE FROM feedback_answers WHERE response_id IN
  (SELECT id FROM feedback_responses WHERE property_id = <id> AND source = 'MIGRATED');
DELETE FROM feedback_responses WHERE property_id = <id> AND source = 'MIGRATED';
DELETE FROM feedback_questions q WHERE property_id = <id> AND NOT active
  AND NOT EXISTS (SELECT 1 FROM feedback_answers a WHERE a.question_id = q.id);
```

`housekeeper_pin_hash = NULL` — если в строке «Коды до» PIN «не задан»; если «задан» —
прежний хеш не сохранён, задать PIN заново в «Сотрудниках».

---

## 3. Горизонт страницы горничной (V41)

- `properties.housekeeper_horizon_days INTEGER NOT NULL DEFAULT 30`, `CHECK 7..180`.
  Существующие объекты получают 30.
- «Сотрудники» (`/staff`), карточка объекта: «Показывать уборки на ближайшие N дней» —
  `POST /staff/{propertyId}/horizon`, `days` от 7 до 180; вне диапазона — сообщение, значение
  не меняется. Чужой объект — 404 (`TenantIsolationIntegrationTest`, таблица в
  `INTEGRATION_FIRST_RUN.md`).
- Страница горничной не показывает выезды позже `сегодня + N`: и брони, и блокировки
  площадок (раньше брони — без границы, блокировки — 180 дней). Выезд ровно на N-й день
  виден.

## 4. «Открыть даты» горничная не видит

Блокировки читаются `findByUnitTypesInRange`, который отфильтровывает `ignored` и
`cancelled_at`. Это было и раньше; теперь `HousekeeperController.upcomingStays` дополнительно
пропускает такие блокировки сам — график не зависит от того, что вернул запрос.

Тесты:

- `HousekeeperScheduleTest` (без docker): горизонт по умолчанию и настроенный, граница N-го дня,
  `ignored`, удалённая ручная бронь;
- `HousekeeperPageIntegrationTest` (Postgres): вход по PIN, на странице нет открытых дат,
  удалённой ручной брони и выездов за горизонтом; горизонт из «Сотрудников» сохраняется и
  меняет страницу, 6 / 181 / «abc» не сохраняются; ответ на выключенный вопрос — с подписью.

`mvn -o test` с colima (см. `INTEGRATION_BOOKING_WIDGET_V2.md`): 382 теста, пропущенных нет.

## Приёмка на staging

- [ ] Контейнер поднялся с V41
- [ ] «Сотрудники»: поле «Показывать уборки на ближайшие … дней» = 30; сохранить 60 — сообщение,
      значение 60; 200 — сообщение об ошибке, осталось 60
- [ ] Страница горничной (Садовая): выездов дальше горизонта нет
- [ ] Шахматка: «Устранить блокировку» → «Открыть даты» на брони площадки — у горничной выезд
      этой брони пропал; «Закрыть снова» — вернулся
