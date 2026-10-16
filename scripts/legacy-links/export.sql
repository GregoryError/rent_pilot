-- Выгрузка из базы прежнего prod (legacy-rc, база rentoptima): коды ссылок отзывов и
-- горничной, PIN горничной, отзывы объекта и ответы на вопросы. Только чтение.
--
-- Запуск — patches/INTEGRATION_LEGACY_LINKS.md. Коротко:
--   docker exec -i -w /tmp/legacy-links rentoptima-db-1 \
--     psql -U rentoptima -d rentoptima -v name_like='Садовая%' < scripts/legacy-links/export.sql
--
-- Параметры (psql -v):
--   name_like        — шаблон имени объекта для ILIKE, по умолчанию 'Садовая%';
--                      должен найти ровно один активный объект;
--   old_property_id  — id объекта в старой базе, если по имени их несколько.
--
-- Пишет в текущий каталог: property.csv, questions.csv, responses.csv, answers.csv, counts.csv.
-- \copy не подставляет переменные psql, поэтому сначала временные таблицы, потом \copy из них.

\set ON_ERROR_STOP on

\if :{?name_like}
\else
    \set name_like 'Садовая%'
\endif
\if :{?old_property_id}
\else
    \set old_property_id ''
\endif

SELECT set_config('mig.name_like', :'name_like', false) AS mig_name_like,
       set_config('mig.old_property_id', :'old_property_id', false) AS mig_old_property_id \gset

DO $$
DECLARE
    n int;
BEGIN
    IF current_setting('mig.old_property_id') <> '' THEN
        SELECT count(*) INTO n FROM properties WHERE id = current_setting('mig.old_property_id')::bigint;
        IF n <> 1 THEN
            RAISE EXCEPTION 'Объекта id=% в старой базе нет', current_setting('mig.old_property_id');
        END IF;
    ELSE
        SELECT count(*) INTO n FROM properties
        WHERE active AND name ILIKE current_setting('mig.name_like');
        IF n <> 1 THEN
            RAISE EXCEPTION 'По шаблону «%» найдено активных объектов: % (нужен ровно один). '
                'Посмотрите SELECT id, name FROM properties и передайте -v old_property_id=…',
                current_setting('mig.name_like'), n;
        END IF;
    END IF;
END $$;

CREATE TEMP TABLE mig_property AS
SELECT id, tenant_id, name, feedback_code, housekeeper_code, housekeeper_pin_hash
FROM properties
WHERE CASE WHEN current_setting('mig.old_property_id') <> ''
           THEN id = current_setting('mig.old_property_id')::bigint
           ELSE active AND name ILIKE current_setting('mig.name_like') END;

CREATE TEMP TABLE mig_responses AS
SELECT r.id, r.session_id, r.guest_name, r.completed, r.show_to_housekeeper,
       r.created_at, r.updated_at, r.agreed_to_consent_at
FROM feedback_responses r JOIN mig_property p ON p.id = r.property_id
ORDER BY r.id;

CREATE TEMP TABLE mig_answers AS
SELECT a.id, a.response_id, a.question_id, a.numeric_value, a.text_value, a.answered_at
FROM feedback_answers a JOIN mig_responses r ON r.id = a.response_id
ORDER BY a.id;

-- Только вопросы, на которые есть ответы: остальные переносить незачем
CREATE TEMP TABLE mig_questions AS
SELECT q.id, q.question_text, q.question_type, q.sort_order, q.active
FROM feedback_questions q
WHERE q.id IN (SELECT question_id FROM mig_answers)
ORDER BY q.id;

CREATE TEMP TABLE mig_counts AS
SELECT (SELECT count(*) FROM mig_responses) AS responses,
       (SELECT count(*) FROM mig_answers)   AS answers,
       (SELECT count(*) FROM mig_questions) AS questions;

\echo
\echo '=== Объект в старой базе'
SELECT id, name, feedback_code, housekeeper_code,
       CASE WHEN coalesce(housekeeper_pin_hash, '') = '' THEN 'НЕ ЗАДАН' ELSE 'задан' END AS pin
FROM mig_property;

\echo '=== Будет выгружено'
SELECT * FROM mig_counts;

\copy mig_property  TO 'property.csv'  WITH (FORMAT csv, HEADER true)
\copy mig_questions TO 'questions.csv' WITH (FORMAT csv, HEADER true)
\copy mig_responses TO 'responses.csv' WITH (FORMAT csv, HEADER true)
\copy mig_answers   TO 'answers.csv'   WITH (FORMAT csv, HEADER true)
\copy mig_counts    TO 'counts.csv'    WITH (FORMAT csv, HEADER true)

\echo '=== Готово: property.csv, questions.csv, responses.csv, answers.csv, counts.csv'
