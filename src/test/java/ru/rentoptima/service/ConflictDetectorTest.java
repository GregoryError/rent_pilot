package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.entity.AlertEvent;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.service.AvailabilityService.DayOccupancy;
import ru.rentoptima.service.ConflictDetector.ConflictPeriod;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConflictDetector и тексты алертов")
class ConflictDetectorTest {

    private static final Long UT = 7L;
    private static final Long SUTOCHNO = 5L;

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private static Booking manualBooking(int from, int to) {
        Booking b = new Booking();
        b.setUnitTypeId(UT);
        b.setDataSource("MANUAL");
        b.setCheckIn(d(from));
        b.setCheckOut(d(to));
        return b;
    }

    private static CalendarBlock icalBlock(int from, int to) {
        CalendarBlock b = new CalendarBlock();
        b.setUnitTypeId(UT);
        b.setBlockType(CalendarBlock.BlockType.CHANNEL_SYNC);
        b.setChannelId(SUTOCHNO);
        b.setFromDate(d(from));
        b.setToDate(d(to));
        return b;
    }

    private static List<ConflictPeriod> detect(List<Booking> bookings, List<CalendarBlock> blocks,
                                               int capacity, LocalDate today) {
        Map<LocalDate, DayOccupancy> days =
                AvailabilityService.buildDetails(List.of(UT), bookings, blocks, d(1), d(30)).get(UT);
        return ConflictDetector.findPeriods(UT, capacity, days, today);
    }

    @Test
    @DisplayName("подряд идущие конфликтные ночи склеиваются в один период")
    void consecutiveDaysMerge() {
        List<ConflictPeriod> periods = detect(
                List.of(manualBooking(10, 14)), List.of(icalBlock(11, 13)), 1, d(1));

        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).from()).isEqualTo(d(11));
        assertThat(periods.get(0).toExclusive()).isEqualTo(d(13));
        assertThat(periods.get(0).channelIds()).containsExactly(SUTOCHNO);
    }

    @Test
    @DisplayName("разрыв между конфликтами даёт два периода")
    void gapSplitsPeriods() {
        List<ConflictPeriod> periods = detect(
                List.of(manualBooking(10, 20)),
                List.of(icalBlock(11, 12), icalBlock(15, 17)), 1, d(1));

        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).toExclusive()).isEqualTo(d(12));
        assertThat(periods.get(1).from()).isEqualTo(d(15));
        assertThat(periods.get(1).toExclusive()).isEqualTo(d(17));
    }

    @Test
    @DisplayName("прошедшие ночи отбрасываются, ключ дедупликации от этого не меняется")
    void pastDaysAreClippedAndKeyIsStable() {
        List<Booking> bookings = List.of(manualBooking(10, 14));
        List<CalendarBlock> blocks = List.of(icalBlock(10, 14));

        ConflictPeriod before = detect(bookings, blocks, 1, d(1)).get(0);
        ConflictPeriod midway = detect(bookings, blocks, 1, d(12)).get(0);

        assertThat(before.from()).isEqualTo(d(10));
        assertThat(midway.from()).isEqualTo(d(12));
        assertThat(midway.dedupKey()).isEqualTo(before.dedupKey());
        assertThat(detect(bookings, blocks, 1, d(14))).isEmpty();
    }

    @Test
    @DisplayName("в пределах вместимости конфликта нет")
    void withinCapacityIsNotConflict() {
        assertThat(detect(List.of(manualBooking(10, 14)), List.of(icalBlock(11, 13)), 2, d(1)))
                .isEmpty();
    }

    @Test
    @DisplayName("ночи периода пишутся по-человечески")
    void formatNights() {
        assertThat(AlertSchedulerService.formatNights(d(12), d(13))).isEqualTo("12 ноября");
        assertThat(AlertSchedulerService.formatNights(d(12), d(15))).isEqualTo("12–14 ноября");
        assertThat(AlertSchedulerService.formatNights(d(29), LocalDate.of(2026, 12, 3)))
                .isEqualTo("29 ноября – 2 декабря");
    }

    @Test
    @DisplayName("текст конфликта называет объект, ночи и площадку")
    void conflictText() {
        ConflictPeriod p = new ConflictPeriod(UT, d(12), d(15), Set.of(SUTOCHNO));

        assertThat(AlertSchedulerService.conflictText("Садовая / Основной", p, Map.of(SUTOCHNO, "Суточно")))
                .isEqualTo("Садовая / Основной: 12–14 ноября (3 ночи) — «Суточно»");
    }

    @Test
    @DisplayName("сообщение ведёт на шахматку с первой конфликтной даты")
    void composeMessageLinksToGrid() {
        AlertEvent a = new AlertEvent();
        a.setAlertType(AlertEvent.AlertType.CONFLICT);
        a.setFromDate(d(12));
        a.setToDate(d(15));
        a.setMessage("Садовая / Основной: 12–14 ноября (3 ночи)");

        String text = AlertSchedulerService.composeMessage(List.of(a), "https://staging.optirent.ru");

        assertThat(text).contains("• Садовая / Основной: 12–14 ноября (3 ночи)");
        assertThat(text).contains("https://staging.optirent.ru/calendar/grid?from=2026-11-12&days=30");
        assertThat(text).doesNotContain("канал не синхронизируется");
    }
}
