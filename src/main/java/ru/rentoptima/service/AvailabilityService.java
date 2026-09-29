package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.channel.ical.ICalWriter;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.CalendarBlockRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Единый источник правды о занятости категории номеров.
 * <p>
 * Занятость складывается из двух независимых источников: броней
 * ({@code bookings}, деньги и гость) и блокировок ({@code calendar_blocks},
 * просто «занято»). Раньше шахматка знала только про брони — из-за этого
 * ремонт или бронь по телефону не защищали даты от продажи на площадке.
 * <p>
 * Модель учитывает {@code unit_count}: у мини-отеля категория «Стандарт» с
 * восемью номерами остаётся доступной, пока занято меньше восьми. Для квартиры
 * с {@code unit_count = 1} это вырождается в привычное «занято/свободно».
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final BookingRepository bookingRepo;
    private final CalendarBlockRepository blockRepo;

    /**
     * Сколько единиц категории занято в каждую дату окна [from, to).
     * Даты без занятости в карте отсутствуют.
     *
     * @param excludeChannelId если не null, блокировки этого канала не считаются.
     *                         Нужно для iCal-экспорта: нельзя отдавать площадке
     *                         обратно ту занятость, которую она же нам и прислала,
     *                         иначе возникает самоподдерживающаяся блокировка,
     *                         которую потом невозможно снять.
     */
    @Transactional(readOnly = true)
    public Map<LocalDate, Integer> occupancyByDate(Long unitTypeId,
                                                    LocalDate from,
                                                    LocalDate to,
                                                    Long excludeChannelId) {
        Map<LocalDate, Integer> occupancy = new TreeMap<>();
        List<Long> ids = List.of(unitTypeId);

        for (Booking b : bookingRepo.findActiveByUnitTypesInRange(ids, from, to)) {
            addRange(occupancy, b.getCheckIn(), b.getCheckOut(), from, to);
        }
        for (CalendarBlock b : blockRepo.findByUnitTypesInRange(ids, from, to)) {
            if (excludeChannelId != null && excludeChannelId.equals(b.getChannelId())) continue;
            addRange(occupancy, b.getFromDate(), b.getToDate(), from, to);
        }
        return occupancy;
    }

    /**
     * Даты, в которые категория распродана полностью (занято >= unit_count).
     * Именно они уходят во внешний iCal-фид.
     */
    @Transactional(readOnly = true)
    public List<LocalDate> soldOutDates(UnitType unitType,
                                        LocalDate from,
                                        LocalDate to,
                                        Long excludeChannelId) {
        int capacity = unitType.getUnitCount() == null ? 1 : Math.max(1, unitType.getUnitCount());
        Map<LocalDate, Integer> occ =
                occupancyByDate(unitType.getId(), from, to, excludeChannelId);

        List<LocalDate> result = new ArrayList<>();
        for (Map.Entry<LocalDate, Integer> e : occ.entrySet()) {
            if (e.getValue() >= capacity) result.add(e.getKey());
        }
        return result;
    }

    /**
     * Схлопывает распроданные даты в непрерывные интервалы для iCal.
     * <p>
     * Отдавать по событию на каждую ночь технически можно, но площадки хуже
     * переваривают тысячи VEVENT, да и фид раздувается. Один интервал на серию
     * подряд идущих занятых ночей — компактнее и читаемее.
     */
    @Transactional(readOnly = true)
    public List<ICalWriter.BusyPeriod> busyPeriods(UnitType unitType,
                                                    LocalDate from,
                                                    LocalDate to,
                                                    Long excludeChannelId) {
        List<LocalDate> dates = soldOutDates(unitType, from, to, excludeChannelId);
        return mergeToPeriods(dates, unitType.getId());
    }

    /** Склейка отсортированного списка дат в полуоткрытые интервалы [start, end). */
    static List<ICalWriter.BusyPeriod> mergeToPeriods(List<LocalDate> sortedDates, Long unitTypeId) {
        List<ICalWriter.BusyPeriod> periods = new ArrayList<>();
        if (sortedDates.isEmpty()) return periods;

        LocalDate start = sortedDates.get(0);
        LocalDate prev = start;

        for (int i = 1; i < sortedDates.size(); i++) {
            LocalDate d = sortedDates.get(i);
            if (d.equals(prev.plusDays(1))) {
                prev = d;
                continue;
            }
            periods.add(period(unitTypeId, start, prev));
            start = d;
            prev = d;
        }
        periods.add(period(unitTypeId, start, prev));
        return periods;
    }

    private static ICalWriter.BusyPeriod period(Long unitTypeId, LocalDate start, LocalDate lastNight) {
        // DTEND в iCal не включителен — это дата выезда, т.е. последняя ночь + 1
        LocalDate end = lastNight.plusDays(1);
        String uid = "ut%d-%s".formatted(unitTypeId, start);
        return new ICalWriter.BusyPeriod(uid, start, end, "Занято");
    }

    /**
     * Сводка занятости по нескольким категориям сразу — для шахматки.
     * Ключ внешней карты — unitTypeId.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<LocalDate, Integer>> occupancyGrid(List<Long> unitTypeIds,
                                                             LocalDate from,
                                                             LocalDate to) {
        Map<Long, Map<LocalDate, Integer>> grid = new HashMap<>();
        if (unitTypeIds.isEmpty()) return grid;

        for (Long id : unitTypeIds) grid.put(id, new TreeMap<>());

        for (Booking b : bookingRepo.findActiveByUnitTypesInRange(unitTypeIds, from, to)) {
            Map<LocalDate, Integer> m = grid.get(b.getUnitTypeId());
            if (m != null) addRange(m, b.getCheckIn(), b.getCheckOut(), from, to);
        }
        for (CalendarBlock b : blockRepo.findByUnitTypesInRange(unitTypeIds, from, to)) {
            Map<LocalDate, Integer> m = grid.get(b.getUnitTypeId());
            if (m != null) addRange(m, b.getFromDate(), b.getToDate(), from, to);
        }
        return grid;
    }

    /** Инкрементирует счётчик занятости по каждой ночи интервала, обрезая по окну. */
    private static void addRange(Map<LocalDate, Integer> target,
                                 LocalDate start, LocalDate end,
                                 LocalDate windowFrom, LocalDate windowTo) {
        if (start == null || end == null) return;
        LocalDate d = start.isBefore(windowFrom) ? windowFrom : start;
        LocalDate stop = end.isAfter(windowTo) ? windowTo : end;
        while (d.isBefore(stop)) {
            target.merge(d, 1, Integer::sum);
            d = d.plusDays(1);
        }
    }
}
