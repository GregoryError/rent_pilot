package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.service.WidgetCalendar.Day;
import ru.rentoptima.service.WidgetCalendar.Rules;
import ru.rentoptima.service.WidgetCalendar.Stay;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Виджет бронирования: календарь и ближайшие свободные даты")
class WidgetCalendarTest {

    /** 2 ноября 2026 — понедельник. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private static Rules rules(int minNights, Set<DayOfWeek> noCheckin, Set<DayOfWeek> noCheckout) {
        return new Rules(TODAY, TODAY.plusDays(60), minNights, 14, noCheckin, noCheckout);
    }

    private static Rules rules(int minNights) {
        return rules(minNights, Set.of(), Set.of());
    }

    private static Set<LocalDate> busy(int... days) {
        Set<LocalDate> set = new HashSet<>();
        for (int day : days) set.add(d(day));
        return set;
    }

    private static Day day(List<Day> days, int n) {
        return days.stream().filter(x -> x.date().equals(d(n))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("занятая ночь закрыта для заезда; выехать в день чужого заезда можно")
    void busyNight() {
        List<Day> days = WidgetCalendar.days(d(2), d(12), busy(5, 6), Map.of(d(4), BigDecimal.valueOf(4000)), rules(1));

        assertThat(day(days, 5).busy()).isTrue();
        assertThat(day(days, 5).checkin()).isEqualTo(WidgetCalendar.BUSY);
        // 5-го заезжает другой гость: наш гость в этот день выезжает
        assertThat(day(days, 5).checkout()).isEqualTo(WidgetCalendar.OK);
        // Ночь с 6-го на 7-е занята — выехать 7-го нельзя, а заехать можно
        assertThat(day(days, 7).checkout()).isEqualTo(WidgetCalendar.BUSY);
        assertThat(day(days, 7).checkin()).isEqualTo(WidgetCalendar.OK);
        assertThat(day(days, 4).price()).isEqualByComparingTo("4000");
        assertThat(day(days, 3).price()).isNull();
    }

    @Test
    @DisplayName("до следующей брони меньше минимального срока — заезд недоступен с пометкой «окно»")
    void minStayGap() {
        // Свободны 3-е и 4-е, с 5-го занято; минимум 3 ночи
        List<Day> days = WidgetCalendar.days(d(2), d(8), busy(5, 6), Map.of(), rules(3));

        assertThat(day(days, 2).checkin()).isEqualTo(WidgetCalendar.OK);
        assertThat(day(days, 3).checkin()).isEqualTo(WidgetCalendar.GAP);
        assertThat(day(days, 4).checkin()).isEqualTo(WidgetCalendar.GAP);
        assertThat(day(days, 7).checkin()).isEqualTo(WidgetCalendar.OK);
    }

    @Test
    @DisplayName("у конца окна бронирования минимальный срок тоже должен помещаться")
    void minStayAtWindowEnd() {
        Rules rules = new Rules(TODAY, d(10), 3, 14, Set.of(), Set.of());
        List<Day> days = WidgetCalendar.days(d(5), d(10), Set.of(), Map.of(), rules);

        assertThat(day(days, 7).checkin()).isEqualTo(WidgetCalendar.OK);
        assertThat(day(days, 8).checkin()).isEqualTo(WidgetCalendar.GAP);
    }

    @Test
    @DisplayName("запреты заезда и выезда по дням недели")
    void weekdayRules() {
        Rules rules = rules(1, EnumSet.of(DayOfWeek.SUNDAY), EnumSet.of(DayOfWeek.SATURDAY));
        List<Day> days = WidgetCalendar.days(d(2), d(10), Set.of(), Map.of(), rules);

        assertThat(day(days, 8).checkin()).isEqualTo(WidgetCalendar.RULE);   // воскресенье
        assertThat(day(days, 7).checkout()).isEqualTo(WidgetCalendar.RULE);  // суббота
        assertThat(day(days, 7).checkin()).isEqualTo(WidgetCalendar.OK);

        assertThat(WidgetCalendar.check(d(8), d(10), Set.of(), rules)).isEqualTo(WidgetError.NO_CHECKIN_DAY);
        assertThat(WidgetCalendar.check(d(5), d(7), Set.of(), rules)).isEqualTo(WidgetError.NO_CHECKOUT_DAY);
        assertThat(WidgetCalendar.check(d(5), d(8), Set.of(), rules)).isNull();
    }

    @Test
    @DisplayName("проверка проживания: занятая ночь внутри интервала — отказ, ночь выезда не мешает")
    void checkStay() {
        Set<LocalDate> busy = busy(6);
        assertThat(WidgetCalendar.check(d(4), d(7), busy, rules(1))).isEqualTo(WidgetError.DATES_TAKEN);
        assertThat(WidgetCalendar.check(d(4), d(6), busy, rules(1))).isNull();
        assertThat(WidgetCalendar.check(d(4), d(5), busy, rules(2))).isEqualTo(WidgetError.MIN_NIGHTS);
        assertThat(WidgetCalendar.check(d(1), d(3), busy, rules(1))).isEqualTo(WidgetError.DATE_PAST);
    }

    @Test
    @DisplayName("выбранные даты заняты — предлагаются ближайшие свободные той же длины, сначала ближние")
    void alternatives() {
        // Гость хотел 10–13 (3 ночи), заняты ночи 11 и 12
        List<Stay> found = WidgetCalendar.alternatives(d(10), d(13), busy(11, 12), rules(1), 3);

        assertThat(found).hasSize(3);
        assertThat(found).allSatisfy(s ->
                assertThat(java.time.temporal.ChronoUnit.DAYS.between(s.checkin(), s.checkout())).isEqualTo(3));
        // Сдвиг на 2 дня назад (8–11) ближе, чем на 3 вперёд (13–16)
        assertThat(found.get(0)).isEqualTo(new Stay(d(8), d(11)));
        assertThat(found).contains(new Stay(d(13), d(16)));
        assertThat(found).noneMatch(s -> s.checkin().isBefore(TODAY));
    }

    @Test
    @DisplayName("замены не выходят в прошлое и учитывают запреты по дням недели")
    void alternativesRespectRules() {
        Rules rules = rules(1, EnumSet.of(DayOfWeek.WEDNESDAY), Set.of());
        // Гость хотел 3–5 (вторник), занята ночь 3
        List<Stay> found = WidgetCalendar.alternatives(d(3), d(5), busy(3), rules, 3);

        assertThat(found).isNotEmpty();
        assertThat(found).noneMatch(s -> s.checkin().getDayOfWeek() == DayOfWeek.WEDNESDAY);
        assertThat(found).noneMatch(s -> s.checkin().isBefore(TODAY));
    }

    @Test
    @DisplayName("дни недели разбираются и сохраняются; запрет на все семь дней не сохраняется")
    void weekdaysFormat() {
        assertThat(WidgetCalendar.parseDays("1, 7,x,9")).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.SUNDAY);
        assertThat(WidgetCalendar.parseDays(null)).isEmpty();
        assertThat(WidgetCalendar.formatDays(List.of(7, 1, 1))).isEqualTo("1,7");
        assertThat(WidgetCalendar.formatDays(List.of(1, 2, 3, 4, 5, 6, 7))).isEmpty();
    }
}
