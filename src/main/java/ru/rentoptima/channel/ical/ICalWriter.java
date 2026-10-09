package ru.rentoptima.channel.ical;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Сборка iCalendar-фида для отдачи внешним площадкам.
 * <p>
 * Экспортируем только занятость, без цен и персональных данных гостя:
 * фид публичен (доступен по секретному URL без аутентификации), и имя
 * с телефоном гостя там оказаться не должны — это и 152-ФЗ, и просто
 * здравый смысл. В SUMMARY уходит нейтральное «Занято».
 * <p>
 * Формат соответствует профилю, который понимают Airbnb, Booking, Суточно и
 * Островок: VEVENT с {@code DTSTART;VALUE=DATE} и {@code DTEND;VALUE=DATE},
 * где DTEND — дата выезда, не включительно.
 */
public final class ICalWriter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String CRLF = "\r\n";
    private static final String PRODID = "-//RentOptima//Channel Sync MVP//RU";

    private ICalWriter() {
    }

    /**
     * @param calendarName отображаемое имя календаря (X-WR-CALNAME)
     * @param periods      занятые интервалы; {@code to} трактуется как дата выезда.
     *                     Каждый интервал становится отдельным VEVENT — writer ничего
     *                     не склеивает и UID не меняет.
     */
    public static String write(String calendarName, List<BusyPeriod> periods) {
        StringBuilder sb = new StringBuilder(256 + periods.size() * 160);
        sb.append("BEGIN:VCALENDAR").append(CRLF);
        sb.append("VERSION:2.0").append(CRLF);
        sb.append("PRODID:").append(PRODID).append(CRLF);
        sb.append("CALSCALE:GREGORIAN").append(CRLF);
        sb.append("METHOD:PUBLISH").append(CRLF);
        appendFolded(sb, "X-WR-CALNAME:" + escape(calendarName));

        String stamp = LocalDateTime.now(ZoneOffset.UTC).format(STAMP);

        for (BusyPeriod p : periods) {
            sb.append("BEGIN:VEVENT").append(CRLF);
            appendFolded(sb, "UID:" + p.uid());
            sb.append("DTSTAMP:").append(stamp).append(CRLF);
            sb.append("DTSTART;VALUE=DATE:").append(p.from().format(DATE)).append(CRLF);
            sb.append("DTEND;VALUE=DATE:").append(p.to().format(DATE)).append(CRLF);
            appendFolded(sb, "SUMMARY:" + escape(p.summary()));
            if (p.cancelled()) {
                // TRANSPARENT — подстраховка для импортёров, которые не читают STATUS,
                // но смотрят на TRANSP: отменённое событие не должно закрывать даты.
                // SEQUENCE выше, чем у живого события (у него 0 по умолчанию): часть
                // парсеров принимает отмену только как новую ревизию события.
                sb.append("SEQUENCE:1").append(CRLF);
                sb.append("STATUS:CANCELLED").append(CRLF);
                sb.append("TRANSP:TRANSPARENT").append(CRLF);
            } else {
                sb.append("TRANSP:OPAQUE").append(CRLF);
            }
            sb.append("END:VEVENT").append(CRLF);
        }

        sb.append("END:VCALENDAR").append(CRLF);
        return sb.toString();
    }

    /**
     * Занятый интервал для выгрузки.
     *
     * @param uid       полный UID события, пишется в фид как есть: внешний UID блокировки
     *                  либо "optirent-manual-42@optirent.ru" / "block-42@optirent.ru" /
     *                  "booking-42@optirent.ru"
     * @param from      дата заезда, включительно
     * @param to        дата выезда, НЕ включительно
     * @param summary   текст события
     * @param cancelled запись удалена у нас: событие уходит со STATUS:CANCELLED, чтобы
     *                  площадка сняла импортированную блокировку
     */
    public record BusyPeriod(String uid, LocalDate from, LocalDate to, String summary,
                             boolean cancelled) {

        public BusyPeriod(String uid, LocalDate from, LocalDate to, String summary) {
            this(uid, from, to, summary, false);
        }
    }

    /**
     * Сворачивание длинных строк (RFC 5545 §3.1): не длиннее 75 октетов,
     * продолжение начинается с пробела. Считаем именно в байтах UTF-8 и не
     * режем посреди многобайтового символа — иначе кириллица в названии
     * объекта превратится в мусор у площадки.
     */
    private static void appendFolded(StringBuilder sb, String line) {
        byte[] bytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= 75) {
            sb.append(line).append(CRLF);
            return;
        }
        int start = 0;
        boolean first = true;
        while (start < bytes.length) {
            int limit = first ? 75 : 74; // с учётом ведущего пробела
            int end = Math.min(start + limit, bytes.length);
            // откатываемся назад, пока не окажемся на границе UTF-8 символа
            while (end > start && end < bytes.length && (bytes[end] & 0xC0) == 0x80) {
                end--;
            }
            String chunk = new String(bytes, start, end - start,
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!first) sb.append(' ');
            sb.append(chunk).append(CRLF);
            start = end;
            first = false;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\n", "\\n");
    }
}
