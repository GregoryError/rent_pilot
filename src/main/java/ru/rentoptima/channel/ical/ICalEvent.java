package ru.rentoptima.channel.ical;

import java.time.LocalDate;

/**
 * Событие занятости, вычитанное из VEVENT.
 *
 * @param uid       UID из VEVENT — ключ идемпотентности импорта
 * @param start     дата заезда, включительно
 * @param end       дата выезда, НЕ включительно (полуоткрытый интервал, как в iCal DTEND)
 * @param summary   текст SUMMARY, идёт в reason блокировки
 * @param cancelled STATUS:CANCELLED — событие снято на стороне площадки
 */
public record ICalEvent(
        String uid,
        LocalDate start,
        LocalDate end,
        String summary,
        boolean cancelled
) {
    /** Число занятых ночей. */
    public long nights() {
        return end.toEpochDay() - start.toEpochDay();
    }
}
