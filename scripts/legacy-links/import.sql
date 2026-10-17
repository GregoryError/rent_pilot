-- Загрузка в новую базу (main, бывший staging) того, что выгрузил export.sql:
--   * объект получает старые feedback_code, housekeeper_code и housekeeper_pin_hash —
--     розданные /feedback/<код> и /housekeeper/<код> открываются как раньше
--     (горничной один раз нужно ввести PIN: в cookie входа зашит id объекта);
--   * отзывы объекта: property_id = новый объект, booking_id = NULL, source = 'MIGRATED',
--     даты сохраняются; ответы — к сопоставленным вопросам;
--   * вопросы сопоставляются по тексту (без учёта регистра и крайних пробелов) и типу среди
--     вопросов хозяина нового объекта (общих и этого объекта); несопоставленные создаются
--     у объекта неактивными — в анкете их нет, ответы не теряются.
-- Коды /q/ и /f/ печатных карточек (widget_qr_links) не трогаются.
--
-- Всё в одной транзакции: любая проверка не прошла — ничего не изменено.
-- Повторный запуск безопасен: отзывы с уже существующим session_id пропускаются,
-- вопросы находятся созданные в прошлый раз.
--
-- Запуск — patches/INTEGRATION_LEGACY_LINKS.md. Коротко:
--   docker exec -i -w /tmp/legacy-links rentoptima-staging-db \
--     psql -U rentoptima -d rentoptima_staging -v new_property_id=5 -v dry_run=1 \
--     < scripts/legacy-links/import.sql
--
-- Параметры (psql -v):
--   new_property_id  — id объекта в новой базе, обязателен; имя объекта должно начинаться
--                      с «Садовая» (защита от опечатки в id);
--   dry_run=1        — всё проверить и выполнить, отчёт напечатать, в конце ROLLBACK.

\set ON_ERROR_STOP on

\if :{?new_property_id}
\else
    \echo 'Не задан new_property_id: psql … -v new_property_id=<id объекта в новой базе>'
    DO $$ BEGIN RAISE EXCEPTION 'new_property_id не задан'; END $$;
\endif

BEGIN;

SELECT set_config('mig.new_property_id', :'new_property_id', true) AS mig_new_property_id \gset

CREATE TEMP TABLE mig_property (
    id BIGINT, tenant_id BIGINT, name TEXT,
    feedback_code TEXT, housekeeper_code TEXT, housekeeper_pin_hash TEXT
) ON COMMIT DROP;
CREATE TEMP TABLE mig_questions (
    id BIGINT, question_text TEXT, question_type TEXT, sort_order INT, active BOOLEAN,
    new_id BIGINT, created BOOLEAN NOT NULL DEFAULT false
) ON COMMIT DROP;
CREATE TEMP TABLE mig_responses (
    id BIGINT, session_id TEXT, guest_name TEXT, completed BOOLEAN, show_to_housekeeper BOOLEAN,
    created_at TIMESTAMP, updated_at TIMESTAMP, agreed_to_consent_at TIMESTAMP,
    new_id BIGINT, skip BOOLEAN NOT NULL DEFAULT false
) ON COMMIT DROP;
CREATE TEMP TABLE mig_answers (
    id BIGINT, response_id BIGINT, question_id BIGINT,
    numeric_value INT, text_value TEXT, answered_at TIMESTAMP
) ON COMMIT DROP;
CREATE TEMP TABLE mig_counts (responses INT, answers INT, questions INT) ON COMMIT DROP;
CREATE TEMP TABLE mig_report (n SERIAL, line TEXT) ON COMMIT DROP;

\copy mig_property (id, tenant_id, name, feedback_code, housekeeper_code, housekeeper_pin_hash) FROM 'property.csv' WITH (FORMAT csv, HEADER true)
\copy mig_questions (id, question_text, question_type, sort_order, active) FROM 'questions.csv' WITH (FORMAT csv, HEADER true)
\copy mig_responses (id, session_id, guest_name, completed, show_to_housekeeper, created_at, updated_at, agreed_to_consent_at) FROM 'responses.csv' WITH (FORMAT csv, HEADER true)
\copy mig_answers FROM 'answers.csv' WITH (FORMAT csv, HEADER true)
\copy mig_counts FROM 'counts.csv' WITH (FORMAT csv, HEADER true)

DO $$
DECLARE
    pid        BIGINT := current_setting('mig.new_property_id')::bigint;
    tid        BIGINT;
    pname      TEXT;
    src        mig_property%ROWTYPE;
    other      TEXT;
    exp        mig_counts%ROWTYPE;
    before_resp INT; before_ans INT; before_qr INT;
    after_resp  INT; after_ans  INT; after_qr  INT;
    q_matched INT; q_created INT;
    skipped INT; skipped_other INT;
    ins_resp INT; ins_ans INT; exp_ans INT;
BEGIN
    -- 1. Выгрузка цела
    IF (SELECT count(*) FROM mig_property) <> 1 THEN
        RAISE EXCEPTION 'property.csv: ожидалась одна строка, есть %', (SELECT count(*) FROM mig_property);
    END IF;
    SELECT * INTO src FROM mig_property;
    SELECT * INTO exp FROM mig_counts;
    IF (SELECT count(*) FROM mig_responses) <> exp.responses
       OR (SELECT count(*) FROM mig_answers) <> exp.answers
       OR (SELECT count(*) FROM mig_questions) <> exp.questions THEN
        RAISE EXCEPTION 'Файлы не совпадают с counts.csv (% / % / %): выгрузка неполная',
            exp.responses, exp.answers, exp.questions;
    END IF;
    IF EXISTS (SELECT 1 FROM mig_answers a LEFT JOIN mig_responses r ON r.id = a.response_id WHERE r.id IS NULL)
       OR EXISTS (SELECT 1 FROM mig_answers a LEFT JOIN mig_questions q ON q.id = a.question_id WHERE q.id IS NULL) THEN
        RAISE EXCEPTION 'answers.csv ссылается на отзыв или вопрос, которого нет в выгрузке';
    END IF;
    IF coalesce(src.feedback_code, '') = '' OR coalesce(src.housekeeper_code, '') = '' THEN
        RAISE EXCEPTION 'В выгрузке пустой feedback_code или housekeeper_code';
    END IF;

    -- 2. Объект в новой базе — тот самый
    SELECT tenant_id, name INTO tid, pname FROM properties WHERE id = pid;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Объекта id=% в новой базе нет', pid;
    END IF;
    IF pname NOT ILIKE 'Садовая%' THEN
        RAISE EXCEPTION 'Объект id=% называется «%», а не «Садовая…» — проверьте new_property_id', pid, pname;
    END IF;

    -- 3. Старые коды не заняты другим объектом
    SELECT string_agg(format('id=%s «%s»', id, name), ', ') INTO other
    FROM properties WHERE feedback_code = src.feedback_code AND id <> pid;
    IF other IS NOT NULL THEN
        RAISE EXCEPTION 'feedback_code % уже у другого объекта: %', src.feedback_code, other;
    END IF;
    SELECT string_agg(format('id=%s «%s»', id, name), ', ') INTO other
    FROM properties WHERE housekeeper_code = src.housekeeper_code AND id <> pid;
    IF other IS NOT NULL THEN
        RAISE EXCEPTION 'housekeeper_code % уже у другого объекта: %', src.housekeeper_code, other;
    END IF;

    -- 4. До
    SELECT count(*) INTO before_resp FROM feedback_responses WHERE property_id = pid;
    SELECT count(*) INTO before_ans FROM feedback_answers a
        JOIN feedback_responses r ON r.id = a.response_id WHERE r.property_id = pid;
    SELECT count(*) INTO before_qr FROM widget_qr_links WHERE tenant_id = tid;
    INSERT INTO mig_report(line) VALUES
        (format('Объект: id=%s «%s» (в старой базе id=%s «%s»)', pid, pname, src.id, src.name)),
        (format('Выгрузка: отзывов %s, ответов %s, вопросов %s', exp.responses, exp.answers, exp.questions)),
        (format('До: отзывов у объекта %s, ответов %s', before_resp, before_ans)),
        (format('Коды до: feedback_code %s, housekeeper_code %s, PIN %s',
            (SELECT feedback_code FROM properties WHERE id = pid),
            (SELECT housekeeper_code FROM properties WHERE id = pid),
            (SELECT CASE WHEN coalesce(housekeeper_pin_hash, '') = '' THEN 'не задан' ELSE 'задан' END
             FROM properties WHERE id = pid)));

    -- 5. Коды и PIN
    UPDATE properties
    SET feedback_code = src.feedback_code,
        housekeeper_code = src.housekeeper_code,
        housekeeper_pin_hash = nullif(src.housekeeper_pin_hash, ''),
        updated_at = now()
    WHERE id = pid;

    -- 6. Вопросы: сопоставление по тексту и типу, активные и общие — в приоритете
    UPDATE mig_questions q SET new_id = m.new_id
    FROM (
        SELECT DISTINCT ON (o.id) o.id AS old_id, n.id AS new_id
        FROM mig_questions o
        JOIN feedback_questions n
          ON n.tenant_id = tid
         AND (n.property_id IS NULL OR n.property_id = pid)
         AND n.question_type = o.question_type
         AND lower(btrim(n.question_text)) = lower(btrim(o.question_text))
        ORDER BY o.id, n.active DESC, (n.property_id IS NULL) DESC, n.id
    ) m
    WHERE q.id = m.old_id;
    GET DIAGNOSTICS q_matched = ROW_COUNT;

    UPDATE mig_questions
    SET new_id = nextval(pg_get_serial_sequence('feedback_questions', 'id')), created = true
    WHERE new_id IS NULL;
    GET DIAGNOSTICS q_created = ROW_COUNT;

    INSERT INTO feedback_questions (id, tenant_id, property_id, question_text, question_type, sort_order, active)
    SELECT new_id, tid, pid, question_text, question_type, sort_order, false
    FROM mig_questions WHERE created;

    -- 7. Отзывы: уже перенесённые (тот же session_id) пропускаются
    UPDATE mig_responses r SET skip = true
    WHERE EXISTS (SELECT 1 FROM feedback_responses f WHERE f.session_id = r.session_id);
    GET DIAGNOSTICS skipped = ROW_COUNT;
    SELECT count(*) INTO skipped_other FROM mig_responses r
    JOIN feedback_responses f ON f.session_id = r.session_id AND f.property_id <> pid;

    UPDATE mig_responses
    SET new_id = nextval(pg_get_serial_sequence('feedback_responses', 'id'))
    WHERE NOT skip;

    INSERT INTO feedback_responses (id, property_id, booking_id, session_id, guest_name, guest_phone,
                                    completed, show_to_housekeeper, created_at, updated_at,
                                    agreed_to_consent_at, source)
    SELECT new_id, pid, NULL, session_id, guest_name, NULL,
           completed, show_to_housekeeper, created_at, updated_at,
           agreed_to_consent_at, 'MIGRATED'
    FROM mig_responses WHERE NOT skip ORDER BY id;
    GET DIAGNOSTICS ins_resp = ROW_COUNT;

    SELECT count(*) INTO exp_ans FROM mig_answers a JOIN mig_responses r ON r.id = a.response_id AND NOT r.skip;
    INSERT INTO feedback_answers (response_id, question_id, numeric_value, text_value, answered_at)
    SELECT r.new_id, q.new_id, a.numeric_value, a.text_value, a.answered_at
    FROM mig_answers a
    JOIN mig_responses r ON r.id = a.response_id AND NOT r.skip
    JOIN mig_questions q ON q.id = a.question_id
    ORDER BY a.id;
    GET DIAGNOSTICS ins_ans = ROW_COUNT;

    -- 8. После
    SELECT count(*) INTO after_resp FROM feedback_responses WHERE property_id = pid;
    SELECT count(*) INTO after_ans FROM feedback_answers a
        JOIN feedback_responses r ON r.id = a.response_id WHERE r.property_id = pid;
    SELECT count(*) INTO after_qr FROM widget_qr_links WHERE tenant_id = tid;

    IF ins_resp <> exp.responses - skipped OR after_resp <> before_resp + ins_resp THEN
        RAISE EXCEPTION 'Отзывы не сошлись: перенесено %, ожидалось %, было %, стало %',
            ins_resp, exp.responses - skipped, before_resp, after_resp;
    END IF;
    IF ins_ans <> exp_ans OR after_ans <> before_ans + ins_ans THEN
        RAISE EXCEPTION 'Ответы не сошлись: перенесено %, ожидалось %, было %, стало %',
            ins_ans, exp_ans, before_ans, after_ans;
    END IF;
    IF after_qr <> before_qr THEN
        RAISE EXCEPTION 'Изменилось число кодов карточек /q/ /f/: было %, стало %', before_qr, after_qr;
    END IF;
    IF (SELECT feedback_code FROM properties WHERE id = pid) <> src.feedback_code
       OR (SELECT housekeeper_code FROM properties WHERE id = pid) <> src.housekeeper_code THEN
        RAISE EXCEPTION 'Коды объекта не записались';
    END IF;

    INSERT INTO mig_report(line) VALUES
        (format('Коды после: feedback_code %s, housekeeper_code %s, PIN %s', src.feedback_code,
            src.housekeeper_code, CASE WHEN coalesce(src.housekeeper_pin_hash, '') = '' THEN 'НЕ ЗАДАН' ELSE 'перенесён' END)),
        (format('Вопросы: сопоставлено %s, создано неактивными %s', q_matched, q_created)),
        (format('Отзывы: перенесено %s, пропущено как уже перенесённые %s%s', ins_resp, skipped,
            CASE WHEN skipped_other > 0 THEN format(' (из них %s — у другого объекта!)', skipped_other) ELSE '' END)),
        (format('Ответы: перенесено %s', ins_ans)),
        (format('После: отзывов у объекта %s, ответов %s', after_resp, after_ans)),
        (format('Коды карточек /q/ /f/ у хозяина: %s, не изменились', after_qr));
END $$;

\echo
\echo '=== Отчёт'
SELECT line AS "перенос" FROM mig_report ORDER BY n;

\echo '=== Созданные неактивные вопросы (в анкете их нет; ответы на них видны с подписью вопроса)'
SELECT new_id AS id, question_type, question_text FROM mig_questions WHERE created ORDER BY id;

\if :{?dry_run}
    ROLLBACK;
    \echo '=== dry_run: всё откатано, база не изменилась'
\else
    COMMIT;
    \echo '=== Записано'
\endif
