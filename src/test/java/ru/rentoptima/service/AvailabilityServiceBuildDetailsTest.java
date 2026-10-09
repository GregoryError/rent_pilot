package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.service.AvailabilityService.DayOccupancy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AvailabilityService.buildDetails")
class AvailabilityServiceBuildDetailsTest {

    private static final Long UT = 7L;

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

    private static CalendarBlock block(CalendarBlock.BlockType type, Long channelId, int from, int to) {
        CalendarBlock b = new CalendarBlock();
        b.setUnitTypeId(UT);
        b.setBlockType(type);
        b.setChannelId(channelId);
        b.setFromDate(d(from));
        b.setToDate(d(to));
        return b;
    }

    private static Map<LocalDate, DayOccupancy> build(List<Booking> bookings, List<CalendarBlock> blocks) {
        return AvailabilityService.buildDetails(List.of(UT), bookings, blocks, d(1), d(30)).get(UT);
    }

    @Test
    @DisplayName("ручная бронь и её парный блок считаются одной единицей")
    void manualPairIsNotDoubled() {
        Map<LocalDate, DayOccupancy> days = build(
                List.of(manualBooking(10, 12)),
                List.of(block(CalendarBlock.BlockType.MANUAL_BOOKING, null, 10, 12)));

        assertThat(days.keySet()).containsExactly(d(10), d(11));
        assertThat(days.get(d(10)).busy()).isEqualTo(1);
        assertThat(days.get(d(10)).conflict(1)).isFalse();
    }

    @Test
    @DisplayName("ручная запись + iCal-блок на одном дне при вместимости 1 — конфликт")
    void manualPlusChannelIsConflict() {
        Map<LocalDate, DayOccupancy> days = build(
                List.of(manualBooking(10, 12)),
                List.of(block(CalendarBlock.BlockType.MANUAL_BOOKING, null, 10, 12),
                        block(CalendarBlock.BlockType.CHANNEL_SYNC, 3L, 11, 13)));

        assertThat(days.get(d(10)).conflict(1)).isFalse();
        assertThat(days.get(d(11)).busy()).isEqualTo(2);
        assertThat(days.get(d(11)).conflict(1)).isTrue();
        assertThat(days.get(d(12)).conflict(1)).isFalse();
    }

    @Test
    @DisplayName("при свободной вместимости пересечение не конфликт")
    void withinCapacityIsNotConflict() {
        Map<LocalDate, DayOccupancy> days = build(
                List.of(),
                List.of(block(CalendarBlock.BlockType.MAINTENANCE, null, 10, 11),
                        block(CalendarBlock.BlockType.CHANNEL_SYNC, 3L, 10, 11)));

        assertThat(days.get(d(10)).conflict(2)).isFalse();
        assertThat(days.get(d(10)).conflict(1)).isTrue();
    }

    @Test
    @DisplayName("два внешних канала на одном дне — не конфликт (эхо)")
    void twoChannelsAreNotConflict() {
        Map<LocalDate, DayOccupancy> days = build(
                List.of(),
                List.of(block(CalendarBlock.BlockType.CHANNEL_SYNC, 3L, 10, 11),
                        block(CalendarBlock.BlockType.CHANNEL_SYNC, 4L, 10, 11)));

        assertThat(days.get(d(10)).busy()).isEqualTo(2);
        assertThat(days.get(d(10)).conflict(1)).isFalse();
    }

    @Test
    @DisplayName("интервал обрезается по окну")
    void clippedToWindow() {
        Map<LocalDate, DayOccupancy> days = AvailabilityService.buildDetails(
                List.of(UT), List.of(),
                List.of(block(CalendarBlock.BlockType.OWNER_USE, null, 1, 20)),
                d(5), d(8)).get(UT);

        assertThat(days.keySet()).containsExactly(d(5), d(6), d(7));
    }

    private static CalendarBlock shadowOf(long manualId, int from, int to) {
        CalendarBlock b = block(CalendarBlock.BlockType.CHANNEL_SYNC, 8L, from, to);
        b.setId(267L);
        b.setExternalUid("199904867");
        b.setShadowOfManualId(manualId);
        return b;
    }

    @Test
    @DisplayName("ручная запись и её тень с площадки — одна занятая единица, без конфликта")
    void shadowOfLiveManualIsHidden() {
        CalendarBlock manual = block(CalendarBlock.BlockType.MANUAL_BOOKING, null, 13, 14);
        manual.setId(265L);

        Map<LocalDate, DayOccupancy> days = build(
                List.of(manualBooking(13, 14)), List.of(manual, shadowOf(265L, 13, 14)));

        assertThat(days.get(d(13)).busy()).isEqualTo(1);
        assertThat(days.get(d(13)).conflict(1)).isFalse();
        // остаётся ручная бронь — на ней сумма и гость
        assertThat(days.get(d(13)).occupants().get(0).manual()).isTrue();
    }

    @Test
    @DisplayName("ручную запись удалили → тень остаётся и держит день занятым как блокировка канала")
    void shadowStaysAfterManualIsDeleted() {
        // удалённая ручная запись в выборку занятости не попадает (cancelled_at)
        Map<LocalDate, DayOccupancy> days = build(List.of(), List.of(shadowOf(265L, 13, 14)));

        assertThat(days.get(d(13)).busy()).isEqualTo(1);
        assertThat(days.get(d(13)).occupants().get(0).manual()).isFalse();
        assertThat(days.get(d(13)).occupants().get(0).channelId()).isEqualTo(8L);
    }
}
