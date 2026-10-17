package ru.rentoptima.service;

import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.BookingWidget;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Файл .ics для гостя — кнопка «Добавить в календарь» на экране подтверждения.
 * <p>
 * К ICalWriter отношения не имеет: тот пишет фид занятости для площадок (события на
 * целый день, без подробностей). Здесь одно событие со временем заезда и выезда.
 * Время «плавающее» (без часового пояса): 14:00 в календаре гостя — это 14:00 там,
 * где находится жильё, в каком бы поясе ни был сам гость при оформлении.
 */
public final class WidgetGuestCalendar {

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private WidgetGuestCalendar() {}

    public static String write(BookingWidget w, Booking b) {
        boolean en = "en".equals(b.getLocale());
        boolean pending = WidgetBookingService.STATUS_PENDING.equals(b.getStatus());
        LocalDateTime start = b.getCheckIn().atTime(w.getCheckinTime());
        LocalDateTime end = b.getCheckOut().atTime(w.getCheckoutTime());

        String summary = w.getTitle() + (pending ? (en ? " (awaiting confirmation)" : " (ждёт подтверждения)") : "");
        StringBuilder description = new StringBuilder();
        description.append(en ? "Booking no. " : "Номер брони: ").append(b.getPublicCode());
        if (w.getContactPhone() != null) {
            description.append("\n").append(en ? "Host phone: " : "Телефон хозяина: ").append(w.getContactPhone());
        }

        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//OptiRent//Booking//RU");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:PUBLISH");
        line(sb, "BEGIN:VEVENT");
        line(sb, "UID:guest-" + b.getExternalId() + "@optirent.ru");
        line(sb, "DTSTAMP:" + LocalDateTime.now(ZoneOffset.UTC).format(UTC));
        line(sb, "DTSTART:" + start.format(LOCAL));
        line(sb, "DTEND:" + end.format(LOCAL));
        line(sb, "SUMMARY:" + escape(summary));
        line(sb, "DESCRIPTION:" + escape(description.toString()));
        if (w.getAddressHint() != null) line(sb, "LOCATION:" + escape(w.getAddressHint()));
        line(sb, "STATUS:" + (pending ? "TENTATIVE" : "CONFIRMED"));
        line(sb, "END:VEVENT");
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    /** RFC 5545: строки не длиннее 75 октетов, продолжение — с пробела; перевод строки CRLF. */
    private static void line(StringBuilder sb, String content) {
        int octets = 0;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (octets + size > 74) {
                sb.append("\r\n ");
                octets = 1;
            }
            sb.appendCodePoint(cp);
            octets += size;
            i += Character.charCount(cp);
        }
        sb.append("\r\n");
    }

    static String escape(String text) {
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n");
    }
}
