package ru.rentoptima.channel.ical;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Минимальный парсер iCalendar (RFC 5545) под задачу импорта занятости.
 * <p>
 * Намеренно не тянем ical4j: нам нужны ровно UID / DTSTART / DTEND / SUMMARY /
 * STATUS из VEVENT, а библиотека добавила бы мегабайты транзитивных зависимостей
 * и свой парсер таймзон ради пяти полей. Поддержанное подмножество стандарта:
 * <ul>
 *   <li>line folding (продолжение строки начинается с пробела или таба);</li>
 *   <li>параметры свойств: {@code DTSTART;VALUE=DATE:20260115};</li>
 *   <li>формы даты {@code YYYYMMDD} и {@code YYYYMMDDTHHMMSS[Z]};</li>
 *   <li>экранирование текста: {@code \n \, \; \\};</li>
 *   <li>{@code STATUS:CANCELLED} как признак снятого события.</li>
 * </ul>
 * Таймзоны (TZID) сознательно игнорируются: посуточная аренда оперирует
 * календарными сутками, и сдвиг времени внутри дня на дату заезда не влияет.
 * <p>
 * Парсер устойчив к мусору: некорректный VEVENT пропускается с warn-логом,
 * остальной фид разбирается дальше. Один битый блок не должен ронять импорт.
 */
@Slf4j
public final class ICalParser {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private ICalParser() {
    }

    public static List<ICalEvent> parse(String ics) {
        List<ICalEvent> events = new ArrayList<>();
        if (ics == null || ics.isBlank()) return events;

        List<String> lines = unfold(ics);

        boolean inEvent = false;
        String uid = null, summary = null, status = null;
        LocalDate start = null, end = null;

        for (String line : lines) {
            String upper = line.toUpperCase();

            if (upper.startsWith("BEGIN:VEVENT")) {
                inEvent = true;
                uid = summary = status = null;
                start = end = null;
                continue;
            }
            if (upper.startsWith("END:VEVENT")) {
                if (inEvent) {
                    ICalEvent ev = build(uid, start, end, summary, status);
                    if (ev != null) events.add(ev);
                }
                inEvent = false;
                continue;
            }
            if (!inEvent) continue;

            int colon = line.indexOf(':');
            if (colon < 0) continue;

            String rawName = line.substring(0, colon);
            String value = line.substring(colon + 1).trim();
            // Отрезаем параметры: "DTSTART;VALUE=DATE" -> "DTSTART"
            int semi = rawName.indexOf(';');
            String name = (semi < 0 ? rawName : rawName.substring(0, semi))
                    .trim().toUpperCase();

            switch (name) {
                case "UID" -> uid = value;
                case "SUMMARY" -> summary = unescape(value);
                case "STATUS" -> status = value.toUpperCase();
                case "DTSTART" -> start = parseDate(value);
                case "DTEND" -> end = parseDate(value);
                default -> {
                    // остальные свойства нам не нужны
                }
            }
        }
        return events;
    }

    private static ICalEvent build(String uid, LocalDate start, LocalDate end,
                                   String summary, String status) {
        if (uid == null || uid.isBlank()) {
            log.warn("iCal: VEVENT без UID пропущен");
            return null;
        }
        if (start == null) {
            log.warn("iCal: VEVENT {} без корректного DTSTART пропущен", uid);
            return null;
        }
        // DTEND необязателен: по RFC отсутствие => однодневное событие.
        LocalDate effectiveEnd = (end == null) ? start.plusDays(1) : end;

        if (!effectiveEnd.isAfter(start)) {
            // Встречается у площадок, ставящих DTEND == DTSTART для одной ночи.
            effectiveEnd = start.plusDays(1);
        }
        return new ICalEvent(uid, start, effectiveEnd, summary,
                "CANCELLED".equals(status));
    }

    /**
     * Разворачивает свёрнутые строки (RFC 5545 §3.1): строка, начинающаяся
     * с пробела или таба, — продолжение предыдущей.
     */
    private static List<String> unfold(String ics) {
        String[] raw = ics.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<String> out = new ArrayList<>(raw.length);
        StringBuilder current = null;

        for (String line : raw) {
            if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                if (current != null) current.append(line.substring(1));
                continue;
            }
            if (current != null) out.add(current.toString());
            current = new StringBuilder(line);
        }
        if (current != null) out.add(current.toString());
        return out;
    }

    /** Поддерживает YYYYMMDD и YYYYMMDDTHHMMSS[Z]; при ошибке возвращает null. */
    private static LocalDate parseDate(String value) {
        if (value == null) return null;
        String v = value.trim();
        int t = v.indexOf('T');
        if (t == 8) v = v.substring(0, 8);
        if (v.length() != 8) {
            log.warn("iCal: неразбираемая дата '{}'", value);
            return null;
        }
        try {
            return LocalDate.parse(v, DATE);
        } catch (Exception e) {
            log.warn("iCal: неразбираемая дата '{}': {}", value, e.getMessage());
            return null;
        }
    }

    private static String unescape(String s) {
        if (s == null) return null;
        return s.replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";")
                .replace("\\\\", "\\");
    }
}
