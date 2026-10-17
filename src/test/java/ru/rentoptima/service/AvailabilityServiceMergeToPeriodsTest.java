package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.channel.ical.ICalWriter;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BusyPeriod у нас именует границы полуоткрытого интервала from/to
 * (from — включительно, to — не включительно), как в iCal DTSTART/DTEND.
 */
@DisplayName("AvailabilityService.mergeToPeriods")
class AvailabilityServiceMergeToPeriodsTest {

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 11, day);
    }

    @Test
    @DisplayName("пустой вход — пустой результат")
    void empty() {
        assertThat(AvailabilityService.mergeToPeriods(List.of(), 1L)).isEmpty();
    }

    @Test
    @DisplayName("одна дата — один период с to = from+1 (полуоткрытый)")
    void singleDate() {
        List<ICalWriter.BusyPeriod> periods =
                AvailabilityService.mergeToPeriods(List.of(d(10)), 42L);
        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).from()).isEqualTo(d(10));
        assertThat(periods.get(0).to()).isEqualTo(d(11));
        assertThat(periods.get(0).uid()).isEqualTo("ut42-2026-11-10");
    }

    @Test
    @DisplayName("три подряд идущих дня склеиваются в один интервал [from, last+1)")
    void contiguousRunMerges() {
        List<ICalWriter.BusyPeriod> periods = AvailabilityService.mergeToPeriods(
                List.of(d(10), d(11), d(12)), 42L);
        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).from()).isEqualTo(d(10));
        assertThat(periods.get(0).to()).isEqualTo(d(13));
    }

    @Test
    @DisplayName("две группы через разрыв дают два отдельных периода")
    void twoRunsWithGap() {
        List<ICalWriter.BusyPeriod> periods = AvailabilityService.mergeToPeriods(
                List.of(d(10), d(11), d(15), d(16), d(17)), 42L);
        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).from()).isEqualTo(d(10));
        assertThat(periods.get(0).to()).isEqualTo(d(12));
        assertThat(periods.get(1).from()).isEqualTo(d(15));
        assertThat(periods.get(1).to()).isEqualTo(d(18));
    }

    @Test
    @DisplayName("одиночные даты вокруг серии — правильно вычленяются")
    void singletonsAroundRun() {
        List<ICalWriter.BusyPeriod> periods = AvailabilityService.mergeToPeriods(
                List.of(d(1), d(3), d(4), d(5), d(10)), 7L);
        assertThat(periods).hasSize(3);
        assertThat(periods.get(0).to()).isEqualTo(d(2));
        assertThat(periods.get(1).from()).isEqualTo(d(3));
        assertThat(periods.get(1).to()).isEqualTo(d(6));
        assertThat(periods.get(2).from()).isEqualTo(d(10));
    }
}
