package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.service.DashboardForecastService.MonthForecast;
import ru.rentoptima.service.EffectivePriceService.DateUnitKey;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DashboardForecastService.compute")
class DashboardForecastServiceComputeTest {

    private static final Long UT = 7L;
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate LAST = LocalDate.of(2026, 11, 30);
    private static final BigDecimal PRICE = new BigDecimal("3500");

    private static LocalDate d(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private static UnitType unitType() {
        UnitType ut = new UnitType();
        ut.setId(UT);
        ut.setUnitCount(1);
        return ut;
    }

    private static Booking paidBooking(long id, int from, int to, String amount) {
        Booking b = new Booking();
        b.setId(id);
        b.setUnitTypeId(UT);
        b.setDataSource("RC");
        b.setCheckIn(d(from));
        b.setCheckOut(d(to));
        b.setAmount(new BigDecimal(amount));
        return b;
    }

    private static CalendarBlock block(long id, CalendarBlock.BlockType type, Long channelId, int from, int to) {
        CalendarBlock b = new CalendarBlock();
        b.setId(id);
        b.setUnitTypeId(UT);
        b.setBlockType(type);
        b.setChannelId(channelId);
        b.setFromDate(d(from));
        b.setToDate(d(to));
        return b;
    }

    private static MonthForecast compute(List<Booking> bookings, List<CalendarBlock> blocks, LocalDate today) {
        Map<DateUnitKey, BigDecimal> prices = new HashMap<>();
        for (LocalDate day = FROM; !day.isAfter(LAST); day = day.plusDays(1)) {
            prices.put(new DateUnitKey(UT, day), PRICE);
        }
        return DashboardForecastService.compute(
                List.of(unitType()),
                AvailabilityService.buildDetails(List.of(UT), bookings, blocks, FROM, LAST.plusDays(1)),
                prices, FROM, LAST, today);
    }

    @Test
    @DisplayName("сумма брони раскладывается по ночам, эхо площадки поверх неё не удваивает выручку")
    void paidBookingWithEcho() {
        MonthForecast f = compute(
                List.of(paidBooking(1, 10, 12, "6000")),
                List.of(block(5, CalendarBlock.BlockType.CHANNEL_SYNC, 3L, 10, 12)),
                d(1));

        assertThat(f.actual()).isEqualByComparingTo("6000");
        assertThat(f.estimated()).isEqualByComparingTo("0");
        assertThat(f.stays()).isEqualTo(1);
        assertThat(f.busyNights()).isEqualTo(2);
    }

    @Test
    @DisplayName("iCal-блок без суммы оценивается по плановой цене, ремонт — нет")
    void estimatedFromPlannedPrice() {
        MonthForecast f = compute(
                List.of(),
                List.of(block(5, CalendarBlock.BlockType.CHANNEL_SYNC, 3L, 20, 22),
                        block(6, CalendarBlock.BlockType.MAINTENANCE, null, 25, 26)),
                d(1));

        assertThat(f.actual()).isEqualByComparingTo("0");
        assertThat(f.estimated()).isEqualByComparingTo("7000");
        assertThat(f.stays()).isEqualTo(1);
        assertThat(f.busyNights()).isEqualTo(3);
        assertThat(f.capacityNights()).isEqualTo(30);
        assertThat(f.occupancyPct()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("потенциал — свободные ночи от сегодня до конца месяца по цене хоста")
    void freeFutureValue() {
        MonthForecast f = compute(
                List.of(paidBooking(1, 27, 29, "6000")),
                List.of(),
                d(26));

        // 26..30 ноября — 5 ночей, две заняты
        assertThat(f.freeFutureNights()).isEqualTo(3);
        assertThat(f.freeFutureValue()).isEqualByComparingTo("10500");
        assertThat(f.daily()).hasSize(30);
        assertThat(f.daily().get(24).past()).isTrue();
        assertThat(f.daily().get(25).past()).isFalse();
    }

    @Test
    @DisplayName("бронь с заездом в прошлом месяце даёт выручку, но в счётчик броней не идёт")
    void stayStartedEarlier() {
        Booking b = new Booking();
        b.setId(2L);
        b.setUnitTypeId(UT);
        b.setDataSource("RC");
        b.setCheckIn(LocalDate.of(2026, 10, 30));
        b.setCheckOut(d(3));
        b.setAmount(new BigDecimal("12000"));

        MonthForecast f = compute(List.of(b), List.of(), d(1));

        assertThat(f.actual()).isEqualByComparingTo("6000");
        assertThat(f.stays()).isZero();
    }
}
