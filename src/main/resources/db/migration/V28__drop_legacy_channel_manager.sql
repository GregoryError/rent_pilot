-- V28: интеграция с внешним channel manager'ом убрана из кода, работаем только
-- через iCal-каналы. Колонки (properties.rc_object_id, bookings.rc_booking_id) и
-- сохранённые настройки не удаляются — приводим данные к виду, который понимает
-- код без этой интеграции.

-- 1. Брони, пришедшие через старую синхронизацию уже после V19, остались без
--    data_source. Раньше их отличали от ручных по rc_booking_id; теперь это поле
--    кодом не читается, поэтому помечаем их так же, как остальные исторические.
UPDATE bookings
   SET data_source = 'RC'
 WHERE data_source IS NULL
   AND rc_booking_id IS NOT NULL;

-- 2. Тип канала RC из enum ChannelType удалён. Строки с ним (если есть) нельзя
--    оставить: Hibernate не сможет их прочитать. Удалять тоже нельзя — на канал
--    ссылаются брони. Переводим в выключенный MANUAL.
UPDATE channels
   SET channel_type = 'MANUAL',
       active = FALSE,
       updated_at = NOW()
 WHERE channel_type = 'RC';
