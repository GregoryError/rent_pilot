package ru.rentoptima.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Статистика дашборда без БД: темп бронирований не сравнивает пустые величины, занятость
 * делится на все квартиры, «пустые окна» ищутся внутри одной квартиры.
 */
@DisplayName("Статистика дашборда: темп, занятость, окна")
class BookingStatsServiceTest {

    private static final long TENANT = 7L;

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final PropertyRepository properties = mock(PropertyRepository.class);
    private final UnitTypeRepository unitTypes = mock(UnitTypeRepository.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final BookingStatsService stats = new BookingStatsService(bookings, settings, properties, unitTypes);
    private final List<Booking> data = new ArrayList<>();
    private final List<UnitType> units = new ArrayList<>();

    @BeforeEach
    void repositories() {
        Property p = new Property();
        p.setId(1L);
        when(properties.findByTenantIdAndActiveTrue(TENANT)).thenReturn(List.of(p));
        when(unitTypes.findByTenantIdAndActiveTrue(TENANT)).thenReturn(units);
        when(settings.getIntValue(anyLong(), anyString(), anyInt())).thenReturn(0);
        when(bookings.findActiveInRangeForTenant(any(), any(), any())).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(1), to = inv.getArgument(2);
            return data.stream().filter(b -> !b.getCheckOut().isBefore(from) && !b.getCheckIn().isAfter(to))
                    .sorted(Comparator.comparing(Booking::getCheckIn)).toList();
        });
        when(bookings.findFirstCheckIn(TENANT)).thenAnswer(inv ->
                data.stream().map(Booking::getCheckIn).min(Comparator.naturalOrder()).orElse(null));
        unit(10L, 1);
    }

    private void unit(Long id, int count) {
        UnitType ut = new UnitType();
        ut.setId(id);
        ut.setPropertyId(1L);
        ut.setUnitCount(count);
        units.add(ut);
    }

    private Booking book(Long unitTypeId, LocalDate in, int nights) {
        Property p = new Property();
        p.setId(1L);
        Booking b = new Booking();
        b.setProperty(p);
        b.setUnitTypeId(unitTypeId);
        b.setCheckIn(in);
        b.setCheckOut(in.plusDays(nights));
        b.setNights(nights);
        b.setStatus("BOOKED");
        b.setAmount(BigDecimal.valueOf(1000L * nights));
        data.add(b);
        return b;
    }

    @Test
    @DisplayName("без броней: «мало данных», норма 0, алерта «Загрузка отстаёт» нет")
    void emptyAccountHasNoPaceAlert() {
        BookingStatsService.BookingPace pace = stats.getBookingPace(TENANT);
        assertThat(pace.status()).isEqualTo(BookingStatsService.PACE_NO_DATA);
        assertThat(pace.historicalAvg()).isZero();
        assertThat(stats.getActionItems(TENANT)).noneMatch(i -> i.type().equals("PACE"));
    }

    @Test
    @DisplayName("история короче трёх полных месяцев — сравнения нет, даже если следующий месяц пуст")
    void shortHistoryIsNoData() {
        YearMonth last = YearMonth.now().minusMonths(1);
        book(10L, last.minusMonths(1).atDay(3), 10);   // меньше трёх полных месяцев
        assertThat(stats.getBookingPace(TENANT).status()).isEqualTo(BookingStatsService.PACE_NO_DATA);
    }

    @Test
    @DisplayName("три полных месяца: норма — среднее и по пустым месяцам; пустой следующий месяц — отставание")
    void normIncludesEmptyMonths() {
        YearMonth last = YearMonth.now().minusMonths(1);
        YearMonth first = last.minusMonths(2);
        book(10L, first.atDay(1), first.lengthOfMonth());      // 100%
        // второй и третий месяц пустые → норма = 100 / 3
        BookingStatsService.BookingPace pace = stats.getBookingPace(TENANT);
        assertThat(pace.historicalAvg()).isEqualTo(Math.round(1000.0 / 3) / 10.0);
        assertThat(pace.status()).isEqualTo("BEHIND");
        assertThat(stats.getActionItems(TENANT)).anyMatch(i -> i.type().equals("PACE")
                && i.detail().startsWith(BookingStatsService.monthName(YearMonth.now().plusMonths(1))));
    }

    @Test
    @DisplayName("две квартиры: занятость делится на обе, не больше 100%")
    void occupancyCountsAllUnits() {
        unit(11L, 1);
        YearMonth m = YearMonth.now().plusMonths(1);
        book(10L, m.atDay(1), m.lengthOfMonth());
        book(11L, m.atDay(1), m.lengthOfMonth());
        BookingStatsService.DashboardKpi both = stats.getKpi(TENANT, m.atDay(1), m.atEndOfMonth());
        assertThat(both.occupancy()).isEqualTo(100.0);

        data.remove(1);
        BookingStatsService.DashboardKpi one = stats.getKpi(TENANT, m.atDay(1), m.atEndOfMonth());
        assertThat(one.occupancy()).isEqualTo(50.0);
        assertThat(stats.getBookingPace(TENANT).currentOccupancy()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("без квартир занятость и доход на ночь — 0, без деления на ноль")
    void noUnitsNoDivision() {
        units.clear();
        YearMonth m = YearMonth.now();
        BookingStatsService.DashboardKpi kpi = stats.getKpi(TENANT, m.atDay(1), m.atEndOfMonth());
        assertThat(kpi.occupancy()).isZero();
        assertThat(kpi.netRevPar()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stats.getBookingPace(TENANT).status()).isEqualTo(BookingStatsService.PACE_NO_DATA);
    }

    @Test
    @DisplayName("«пустое окно» — только между бронями одной квартиры")
    void gapsWithinOneUnit() {
        unit(11L, 1);
        LocalDate d = LocalDate.now().plusDays(3);
        book(10L, d, 2);                 // квартира 10: d..d+2
        book(11L, d.plusDays(3), 2);     // другая квартира через день — не окно
        assertThat(stats.detectGaps(TENANT, d, d.plusDays(14))).isEmpty();

        book(10L, d.plusDays(3), 2);     // квартира 10 через день — окно в 1 день
        List<BookingStatsService.GapInfo> gaps = stats.detectGaps(TENANT, d, d.plusDays(14));
        assertThat(gaps).hasSize(1);
        assertThat(ChronoUnit.DAYS.between(gaps.get(0).from(), gaps.get(0).to())).isEqualTo(1);
        assertThat(stats.detectGaps(TENANT, 2L, d, d.plusDays(14))).isEmpty();
    }

    @Test
    @DisplayName("месяц в заголовке — именительный падеж: «Октябрь», не «Октября»")
    void standaloneMonth() {
        assertThat(BookingStatsService.monthName(YearMonth.of(2026, 10))).isEqualTo("Октябрь");
        assertThat(BookingStatsService.monthName(YearMonth.of(2026, 5))).isEqualTo("Май");
        assertThat(BookingStatsService.monthName(YearMonth.of(2026, 11))).isEqualTo("Ноябрь");
    }
}
