package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.service.AvailabilityService.DayOccupancy;
import ru.rentoptima.service.ChannelDiagnosticsService.Propagation;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Диагностика каналов: задержки и наложения")
class ChannelDiagnosticsServiceTest {

    private static LocalDateTime t(int hour, int minute) {
        return LocalDateTime.of(2026, 11, 10, hour, minute);
    }

    @Test
    @DisplayName("задержка считается до первого обращения к фиду после изменения")
    void propagationUsesNextFetch() {
        Propagation p = ChannelDiagnosticsService.propagation(
                List.of(t(10, 5), t(12, 0)),
                List.of(t(10, 0), t(10, 30), t(11, 0)));

        assertThat(p.delays()).containsExactly(Duration.ofMinutes(25));
        assertThat(p.pending()).isEqualTo(1);
    }

    @Test
    @DisplayName("интервалы, медиана и максимум")
    void gapsMedianMax() {
        List<Duration> gaps = ChannelDiagnosticsService.gaps(
                List.of(t(10, 0), t(10, 30), t(11, 30), t(11, 40)));

        assertThat(gaps).containsExactly(
                Duration.ofMinutes(30), Duration.ofMinutes(60), Duration.ofMinutes(10));
        assertThat(ChannelDiagnosticsService.median(gaps)).isEqualTo(Duration.ofMinutes(30));
        assertThat(ChannelDiagnosticsService.max(gaps)).isEqualTo(Duration.ofMinutes(60));
        assertThat(ChannelDiagnosticsService.median(List.of())).isNull();
        assertThat(ChannelDiagnosticsService.median(
                List.of(Duration.ofMinutes(10), Duration.ofMinutes(20)))).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("длительность пишется по-человечески")
    void formatDuration() {
        assertThat(ChannelDiagnosticsService.formatDuration(null)).isEqualTo("—");
        assertThat(ChannelDiagnosticsService.formatDuration(Duration.ofSeconds(20))).isEqualTo("меньше минуты");
        assertThat(ChannelDiagnosticsService.formatDuration(Duration.ofMinutes(45))).isEqualTo("45 мин");
        assertThat(ChannelDiagnosticsService.formatDuration(Duration.ofMinutes(125))).isEqualTo("2 ч 5 мин");
        assertThat(ChannelDiagnosticsService.formatDuration(Duration.ofHours(50))).isEqualTo("2 д 2 ч");
    }

    @Test
    @DisplayName("наложение двух площадок — не конфликт, но попадает в журнал наложений")
    void overlapWithoutManualEntry() {
        Long ut = 7L;
        CalendarBlock a = block(ut, 5L, 11, 13);
        CalendarBlock b = block(ut, 6L, 12, 14);
        LocalDate from = LocalDate.of(2026, 11, 1);
        Map<LocalDate, DayOccupancy> days = AvailabilityService
                .buildDetails(List.of(ut), List.<Booking>of(), List.of(a, b), from, from.plusDays(29))
                .get(ut);

        assertThat(ConflictDetector.findPeriods(ut, 1, days, from)).isEmpty();
        assertThat(ConflictDetector.findOverlaps(ut, 1, days, from))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.from()).isEqualTo(LocalDate.of(2026, 11, 12));
                    assertThat(p.toExclusive()).isEqualTo(LocalDate.of(2026, 11, 13));
                    assertThat(p.channelIds()).containsExactlyInAnyOrder(5L, 6L);
                });
    }

    private static CalendarBlock block(Long unitTypeId, Long channelId, int from, int to) {
        CalendarBlock b = new CalendarBlock();
        b.setUnitTypeId(unitTypeId);
        b.setBlockType(CalendarBlock.BlockType.CHANNEL_SYNC);
        b.setChannelId(channelId);
        b.setFromDate(LocalDate.of(2026, 11, from));
        b.setToDate(LocalDate.of(2026, 11, to));
        return b;
    }
}
