-- V18: default unit_type per property + backfill legacy RC bookings.
--
-- Мотивация:
--   Модель после V16 требует, чтобы брони и каналы висели на unit_type, а не на
--   property напрямую. Легаси RC-брони и все существующие property на проде этому
--   ещё не удовлетворяют. Без этой миграции шахматка и iCal-экспорт скрывают всю
--   историческую занятость (у неё unit_type_id IS NULL и AvailabilityService её
--   не видит), а свежий iCal-канал, подключённый параллельно с RC, не защитит от
--   овербукинга: он не будет знать про уже сущестущие брони.
--
-- Что делаем:
--   1. Для каждой property без единой unit_types-записи создаём «Основной»
--      (unit_count = 1, active). Идемпотентно: повторный прогон ничего не
--      добавит, потому что запись уже будет.
--   2. Для каждой bookings.unit_type_id IS NULL находим «Основной» соответствующей
--      property и прописываем. Если у property несколько «Основной» (не должно быть,
--      но защищаемся) — берём с наименьшим id.
--   3. Для каждой bookings.external_id IS NULL с rc_booking_id != NULL копируем
--      rc_booking_id → external_id. Готовим существующие брони к дедупликации по
--      паре (channel_id, external_id), которая используется всеми новыми адаптерами.
--
-- Что осознанно НЕ делаем:
--   - Не создаём синтетическую строку в channels для «легаси RC» и не проставляем
--     bookings.channel_id. Если бы создали, все новые запросы «взять брони канала X»
--     стали бы неявно возвращать историю, у которой нет реального Channel-адаптера —
--     это скорее вредит, чем помогает. Легаси брони остаются с channel_id IS NULL,
--     и это соответствует их природе: они пришли не из адаптера, а из старой прямой
--     RC-интеграции, от которой мы уходим.

INSERT INTO unit_types (tenant_id, property_id, name, unit_count, active, created_at, updated_at)
SELECT p.tenant_id, p.id, 'Основной', 1, TRUE, NOW(), NOW()
FROM properties p
WHERE NOT EXISTS (
    SELECT 1 FROM unit_types ut WHERE ut.property_id = p.id
);

UPDATE bookings b
SET unit_type_id = (
    SELECT ut.id FROM unit_types ut
    WHERE ut.property_id = b.property_id
      AND ut.name = 'Основной'
      AND ut.active = TRUE
    ORDER BY ut.id
    LIMIT 1
)
WHERE b.unit_type_id IS NULL;

UPDATE bookings
SET external_id = rc_booking_id
WHERE rc_booking_id IS NOT NULL
  AND external_id IS NULL;
