package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.channel.ical.ICalEvent;
import ru.rentoptima.channel.ical.ICalParser;
import ru.rentoptima.channel.ical.ICalWriter;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("iCal-экспорт: одна запись — один VEVENT")
class ICalExportEventsTest {

    private static final Long UT = 3L;

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 10, day);
    }

    private static CalendarBlock block(long id, Long channelId, String externalUid, int from, int to) {
        CalendarBlock b = new CalendarBlock();
        b.setId(id);
        b.setUnitTypeId(UT);
        b.setChannelId(channelId);
        b.setExternalUid(externalUid);
        b.setBlockType(channelId == null
                ? CalendarBlock.BlockType.MANUAL_BOOKING : CalendarBlock.BlockType.CHANNEL_SYNC);
        b.setFromDate(d(from));
        b.setToDate(d(to));
        return b;
    }

    private static Booking booking(long id, String dataSource, Long channelId, int from, int to) {
        Booking b = new Booking();
        b.setId(id);
        b.setUnitTypeId(UT);
        b.setDataSource(dataSource);
        b.setChannelId(channelId);
        b.setCheckIn(d(from));
        b.setCheckOut(d(to));
        return b;
    }

    /** Прогоняет события через настоящий writer и разбирает получившийся фид обратно. */
    private static List<ICalEvent> feed(List<Booking> bookings, List<CalendarBlock> blocks, Long exclude) {
        String ics = ICalWriter.write("Тест",
                AvailabilityService.buildExportEvents(bookings, blocks, exclude));
        return ICalParser.parse(ics);
    }

    @Test
    @DisplayName("три соседние блокировки дают три VEVENT с разными UID и своими датами")
    void adjacentBlocksAreNotMerged() {
        List<ICalEvent> events = feed(List.of(), List.of(
                block(1, null, null, 10, 11),
                block(2, null, null, 11, 12),
                block(3, null, null, 12, 13)), null);

        assertThat(events).hasSize(3);
        assertThat(events).extracting(ICalEvent::uid).containsExactly(
                "optirent-manual-1@optirent.ru", "optirent-manual-2@optirent.ru",
                "optirent-manual-3@optirent.ru");
        assertThat(events).extracting(ICalEvent::start).containsExactly(d(10), d(11), d(12));
        assertThat(events).extracting(ICalEvent::end).containsExactly(d(11), d(12), d(13));
    }

    @Test
    @DisplayName("внешний UID блокировки уходит в фид как есть")
    void externalUidIsKept() {
        List<ICalEvent> events = feed(List.of(), List.of(
                block(1, 9L, "aaa", 10, 11),
                block(2, 9L, "bbb", 11, 12)), null);

        assertThat(events).extracting(ICalEvent::uid).containsExactly("aaa", "bbb");
    }

    @Test
    @DisplayName("86 соседних блокировок из одного канала — 86 VEVENT, а не один на весь период")
    void manyBackToBackBlocks() {
        List<CalendarBlock> blocks = new java.util.ArrayList<>();
        LocalDate start = LocalDate.of(2026, 12, 24);
        for (int i = 0; i < 86; i++) {
            CalendarBlock b = block(100 + i, 9L, String.valueOf(500000 + i), 1, 2);
            b.setFromDate(start.plusDays(i * 3L));
            b.setToDate(start.plusDays(i * 3L + 3));
            blocks.add(b);
        }

        assertThat(feed(List.of(), blocks, null)).hasSize(86);
    }

    @Test
    @DisplayName("блокировки канала-получателя в его фид не попадают")
    void ownChannelIsExcluded() {
        List<ICalEvent> events = feed(
                List.of(booking(7, "AVITO", 9L, 20, 22)),
                List.of(block(1, 9L, "aaa", 10, 11), block(2, 8L, "bbb", 11, 12)), 9L);

        assertThat(events).extracting(ICalEvent::uid).containsExactly("bbb");
    }

    @Test
    @DisplayName("ручная бронь (блокировка + бронь) — одно событие; бронь из RC — отдельное")
    void manualPairIsOneEvent() {
        List<ICalEvent> events = feed(
                List.of(booking(7, "MANUAL", null, 10, 12), booking(8, "RC", null, 12, 15)),
                List.of(block(1, null, null, 10, 12)), null);

        assertThat(events).extracting(ICalEvent::uid).containsExactly(
                "optirent-manual-1@optirent.ru", "booking-8@optirent.ru");
    }

    @Test
    @DisplayName("ручная запись уходит с UID-маркером optirent-manual-<id>, блокировка канала — без него")
    void manualBlockGetsMarkerUid() {
        CalendarBlock maintenance = block(43, null, null, 14, 16);
        maintenance.setBlockType(CalendarBlock.BlockType.MAINTENANCE);

        List<ICalEvent> events = feed(List.of(), List.of(
                block(42, null, null, 10, 12),
                block(7, 9L, "199904867", 12, 14),
                maintenance), null);

        assertThat(events).extracting(ICalEvent::uid).containsExactly(
                "optirent-manual-42@optirent.ru", "199904867", "optirent-manual-43@optirent.ru");
    }

    @Test
    @DisplayName("удалённая ручная запись остаётся в фиде со STATUS:CANCELLED и прежним UID")
    void cancelledManualIsExportedAsCancelled() {
        CalendarBlock cancelled = block(42, null, null, 10, 12);
        cancelled.setCancelledAt(java.time.LocalDateTime.now());

        String ics = ICalWriter.write("Тест", AvailabilityService.buildExportEvents(
                List.of(), List.of(block(1, null, null, 20, 22)), List.of(cancelled), 9L));

        assertThat(ics).containsOnlyOnce("STATUS:CANCELLED");
        List<ICalEvent> events = ICalParser.parse(ics);
        assertThat(events).extracting(ICalEvent::uid).containsExactly(
                "optirent-manual-42@optirent.ru", "optirent-manual-1@optirent.ru");
        assertThat(events).extracting(ICalEvent::cancelled).containsExactly(true, false);
    }

    @Test
    @DisplayName("одинаковый внешний UID от двух каналов не дублируется в фиде")
    void duplicateExternalUidGetsOwnUid() {
        List<ICalEvent> events = feed(List.of(), List.of(
                block(1, 8L, "12345", 10, 11),
                block(2, 9L, "12345", 14, 15)), null);

        assertThat(events).extracting(ICalEvent::uid)
                .containsExactly("12345", "block-2@optirent.ru");
    }
}
