package ru.rentoptima.service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Календарь виджета бронирования: состояние каждого дня и поиск ближайших свободных
 * дат. Без БД — на вход уже посчитанные занятые ночи и цены.
 * <p>
 * Интервал проживания полуоткрытый: {@code [заезд, выезд)}. «Ночь d» — ночь с d на
 * d+1. Выехать можно и в день, ночь которого занята: это день чужого заезда.
 */
public final class WidgetCalendar {

    /** Заезд возможен. */
    public static final String OK = "ok";
    /** Ночь занята. */
    public static final String BUSY = "busy";
    /** Хозяин не принимает заезд / выезд в этот день недели. */
    public static final String RULE = "rule";
    /** До следующей брони меньше ночей, чем минимальный срок. */
    public static final String GAP = "gap";

    /** Дальше стольких дней от выбранной даты замену не предлагаем. */
    static final int ALTERNATIVE_SEARCH_DAYS = 60;

    private WidgetCalendar() {}

    /**
     * Правила проживания виджета.
     *
     * @param maxDate последний день, на который можно назначить выезд
     */
    public record Rules(LocalDate today, LocalDate maxDate, int minNights, int maxNights,
                        Set<DayOfWeek> noCheckin, Set<DayOfWeek> noCheckout) {}

    /**
     * @param checkin  {@link #OK}, {@link #BUSY}, {@link #RULE} или {@link #GAP}
     * @param checkout {@link #OK}, {@link #BUSY} (предыдущая ночь занята) или {@link #RULE}
     * @param price    цена ночи; null — не задана или скрыта
     */
    public record Day(LocalDate date, boolean busy, String checkin, String checkout, BigDecimal price) {}

    /** Дни из {@code [from, to)} с ценой ночи и допустимостью заезда и выезда. */
    public static List<Day> days(LocalDate from, LocalDate to, Set<LocalDate> busyNights,
                                 Map<LocalDate, BigDecimal> prices, Rules rules) {
        List<Day> days = new ArrayList<>();
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            boolean busy = busyNights.contains(d);
            String checkin;
            if (busy) checkin = BUSY;
            else if (rules.noCheckin().contains(d.getDayOfWeek())) checkin = RULE;
            else if (freeRun(d, busyNights, rules) < rules.minNights()) checkin = GAP;
            else checkin = OK;

            String checkout;
            if (busyNights.contains(d.minusDays(1))) checkout = BUSY;
            else if (rules.noCheckout().contains(d.getDayOfWeek())) checkout = RULE;
            else checkout = OK;

            days.add(new Day(d, busy, checkin, checkout, prices.get(d)));
        }
        return days;
    }

    /** Сколько ночей подряд свободно начиная с d, но не больше минимального срока и не дальше окна. */
    private static int freeRun(LocalDate d, Set<LocalDate> busyNights, Rules rules) {
        int run = 0;
        LocalDate night = d;
        while (run < rules.minNights() && night.isBefore(rules.maxDate()) && !busyNights.contains(night)) {
            run++;
            night = night.plusDays(1);
        }
        return run;
    }

    /**
     * Почему нельзя забронировать {@code [checkin, checkout)}.
     *
     * @return код причины ({@link WidgetError}) или null, если проживание допустимо
     */
    public static WidgetError check(LocalDate checkin, LocalDate checkout,
                                    Set<LocalDate> busyNights, Rules rules) {
        WidgetError error = checkRules(checkin, checkout, rules);
        if (error != null) return error;
        for (LocalDate d = checkin; d.isBefore(checkout); d = d.plusDays(1)) {
            if (busyNights.contains(d)) return WidgetError.DATES_TAKEN;
        }
        return null;
    }

    /** Проверка дат по правилам виджета, без занятости. */
    public static WidgetError checkRules(LocalDate checkin, LocalDate checkout, Rules rules) {
        if (checkin == null || checkout == null) return WidgetError.DATES_REQUIRED;
        if (!checkout.isAfter(checkin)) return WidgetError.DATES_ORDER;
        if (checkin.isBefore(rules.today())) return WidgetError.DATE_PAST;
        if (checkout.isAfter(rules.maxDate())) return WidgetError.WINDOW;
        long nights = java.time.temporal.ChronoUnit.DAYS.between(checkin, checkout);
        if (nights < rules.minNights()) return WidgetError.MIN_NIGHTS;
        if (nights > rules.maxNights()) return WidgetError.MAX_NIGHTS;
        if (rules.noCheckin().contains(checkin.getDayOfWeek())) return WidgetError.NO_CHECKIN_DAY;
        if (rules.noCheckout().contains(checkout.getDayOfWeek())) return WidgetError.NO_CHECKOUT_DAY;
        return null;
    }

    /**
     * Ближайшие к выбранным свободные даты той же длины — когда выбранные заняты.
     * Перебирает сдвиги на 1, 2, 3… дней назад и вперёд, ближайшие первыми.
     */
    public static List<Stay> alternatives(LocalDate checkin, LocalDate checkout,
                                          Set<LocalDate> busyNights, Rules rules, int limit) {
        List<Stay> found = new ArrayList<>();
        if (checkin == null || checkout == null || !checkout.isAfter(checkin)) return found;
        long nights = java.time.temporal.ChronoUnit.DAYS.between(checkin, checkout);
        for (int shift = 1; shift <= ALTERNATIVE_SEARCH_DAYS && found.size() < limit; shift++) {
            for (int sign : new int[] {-1, 1}) {
                if (found.size() >= limit) break;
                LocalDate in = checkin.plusDays((long) sign * shift);
                LocalDate out = in.plusDays(nights);
                if (check(in, out, busyNights, rules) == null) found.add(new Stay(in, out));
            }
        }
        return found;
    }

    public record Stay(LocalDate checkin, LocalDate checkout) {}

    /** Разбирает «1,6,7» в дни недели; мусор и пустые значения пропускаются. */
    public static Set<DayOfWeek> parseDays(String csv) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (csv == null) return days;
        for (String part : csv.split(",")) {
            try {
                int n = Integer.parseInt(part.trim());
                if (n >= 1 && n <= 7) days.add(DayOfWeek.of(n));
            } catch (NumberFormatException e) {
                // пропускаем
            }
        }
        return days;
    }

    /** Обратно в «1,6,7»; запрет на все семь дней не сохраняем — он закрыл бы бронирование целиком. */
    public static String formatDays(List<Integer> numbers) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (numbers != null) {
            for (Integer n : numbers) {
                if (n != null && n >= 1 && n <= 7) days.add(DayOfWeek.of(n));
            }
        }
        if (days.size() == 7) return "";
        StringBuilder sb = new StringBuilder();
        for (DayOfWeek d : days) {
            if (sb.length() > 0) sb.append(',');
            sb.append(d.getValue());
        }
        return sb.toString();
    }
}
