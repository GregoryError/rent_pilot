package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.service.AvailabilityService.DayOccupancy;
import ru.rentoptima.service.AvailabilityService.Occupant;
import ru.rentoptima.service.EffectivePriceService.DateUnitKey;
import ru.rentoptima.service.PricingEngine.PricingRecommendation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Цифры главной за месяц: прогноз выручки, занятость, число броней, потенциал.
 * <p>
 * Прогноз = «по броням» (суммы броней, разложенные по ночам) + «оценка» (занятые
 * ночи без суммы — iCal и ручные брони без цены — по плановой цене из
 * EffectivePriceService). Оценочная часть всегда отдаётся отдельно, чтобы UI мог
 * честно пометить её как estimated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardForecastService {

    private final PropertyRepository propertyRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final AvailabilityService availability;
    private final EffectivePriceService effectivePrice;
    private final PricingEngine pricingEngine;

    @Transactional(readOnly = true)
    public MonthForecast forecast(Long tenantId, YearMonth month, LocalDate today) {
        LocalDate from = month.atDay(1);
        LocalDate lastDay = month.atEndOfMonth();
        LocalDate toExclusive = lastDay.plusDays(1);

        List<Property> properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        Map<Long, List<UnitType>> unitTypesByProperty = new HashMap<>();
        List<UnitType> unitTypes = new ArrayList<>();
        for (Property p : properties) {
            List<UnitType> uts = unitTypeRepo.findByPropertyIdAndActiveTrue(p.getId());
            unitTypesByProperty.put(p.getId(), uts);
            unitTypes.addAll(uts);
        }
        List<Long> ids = unitTypes.stream().map(UnitType::getId).toList();

        Map<Long, Map<LocalDate, DayOccupancy>> occupancy =
                availability.occupancyDetails(ids, from, toExclusive);
        Map<DateUnitKey, BigDecimal> prices = effectivePrice.resolveBatch(ids, from, lastDay);

        MonthForecast forecast = compute(unitTypes, occupancy, prices, from, lastDay, today);
        Optimization optimization = optimization(
                properties, unitTypesByProperty, occupancy, prices, from, lastDay, today);
        return forecast.withOptimization(optimization);
    }

    /** Чистый расчёт без БД — покрыт тестом. */
    static MonthForecast compute(List<UnitType> unitTypes,
                                 Map<Long, Map<LocalDate, DayOccupancy>> occupancy,
                                 Map<DateUnitKey, BigDecimal> prices,
                                 LocalDate from, LocalDate lastDay, LocalDate today) {
        int days = (int) (lastDay.toEpochDay() - from.toEpochDay()) + 1;
        BigDecimal[] actualByDay = new BigDecimal[days];
        BigDecimal[] estimatedByDay = new BigDecimal[days];
        for (int i = 0; i < days; i++) {
            actualByDay[i] = BigDecimal.ZERO;
            estimatedByDay[i] = BigDecimal.ZERO;
        }

        long capacityNights = 0;
        long busyNights = 0;
        long freeFutureNights = 0;
        BigDecimal freeFutureValue = BigDecimal.ZERO;
        Set<String> stays = new HashSet<>();

        for (UnitType ut : unitTypes) {
            int capacity = capacityOf(ut);
            Map<LocalDate, DayOccupancy> byDate = occupancy.getOrDefault(ut.getId(), Map.of());

            for (int i = 0; i < days; i++) {
                LocalDate d = from.plusDays(i);
                BigDecimal price = prices.get(new DateUnitKey(ut.getId(), d));
                DayOccupancy day = byDate.get(d);
                int busy = day == null ? 0 : Math.min(capacity, day.busy());
                capacityNights += capacity;
                busyNights += busy;

                if (day != null) {
                    // Сверх вместимости денег не бывает: сначала считаем записи с суммой,
                    // оставшиеся места добираем оценкой. Так эхо площадки поверх
                    // оплаченной брони не удваивает выручку.
                    int unitsLeft = capacity;
                    for (Occupant o : day.occupants()) {
                        if (unitsLeft == 0) break;
                        if (o.nightlyAmount() == null) continue;
                        actualByDay[i] = actualByDay[i].add(o.nightlyAmount());
                        unitsLeft--;
                        countStay(stays, o, from);
                    }
                    for (Occupant o : day.occupants()) {
                        if (unitsLeft == 0) break;
                        if (!o.estimatedSale()) continue;
                        if (price != null) estimatedByDay[i] = estimatedByDay[i].add(price);
                        unitsLeft--;
                        countStay(stays, o, from);
                    }
                }

                int free = capacity - busy;
                if (free > 0 && !d.isBefore(today)) {
                    freeFutureNights += free;
                    if (price != null) {
                        freeFutureValue = freeFutureValue.add(price.multiply(BigDecimal.valueOf(free)));
                    }
                }
            }
        }

        List<DayRevenue> daily = new ArrayList<>(days);
        BigDecimal actual = BigDecimal.ZERO;
        BigDecimal estimated = BigDecimal.ZERO;
        for (int i = 0; i < days; i++) {
            LocalDate d = from.plusDays(i);
            daily.add(new DayRevenue(d, actualByDay[i], estimatedByDay[i], d.isBefore(today)));
            actual = actual.add(actualByDay[i]);
            estimated = estimated.add(estimatedByDay[i]);
        }

        double occupancyPct = capacityNights == 0 ? 0 : busyNights * 100.0 / capacityNights;
        return new MonthForecast(actual, estimated, Math.round(occupancyPct * 10) / 10.0,
                busyNights, capacityNights, stays.size(),
                freeFutureNights, freeFutureValue, daily, null);
    }

    /** Бронь считаем в том месяце, где у неё заезд — иначе длинная бронь посчитается дважды. */
    private static void countStay(Set<String> stays, Occupant o, LocalDate monthStart) {
        if (o.start() != null && !o.start().isBefore(monthStart)) stays.add(o.key());
    }

    /**
     * «Потенциал оптимизации»: свободные будущие ночи месяца по ценам хоста против
     * рекомендованных PricingEngine. Считается только для объектов с одной категорией
     * на один номер — рекомендации движка привязаны к объекту, а не к категории.
     * <p>
     * Ничего не пушит и не меняет: это сравнение двух чисел для виджета.
     */
    private Optimization optimization(List<Property> properties,
                                      Map<Long, List<UnitType>> unitTypesByProperty,
                                      Map<Long, Map<LocalDate, DayOccupancy>> occupancy,
                                      Map<DateUnitKey, BigDecimal> prices,
                                      LocalDate from, LocalDate lastDay, LocalDate today) {
        if (lastDay.isBefore(today)) return null;
        LocalDate recFrom = today.isBefore(from) ? from : today;

        BigDecimal userTotal = BigDecimal.ZERO;
        BigDecimal recommendedTotal = BigDecimal.ZERO;
        int nights = 0;

        for (Property p : properties) {
            List<UnitType> uts = unitTypesByProperty.getOrDefault(p.getId(), List.of());
            if (uts.size() != 1 || capacityOf(uts.get(0)) != 1) continue;
            UnitType ut = uts.get(0);
            Map<LocalDate, DayOccupancy> byDate = occupancy.getOrDefault(ut.getId(), Map.of());

            List<PricingRecommendation> recs;
            try {
                recs = pricingEngine.calculateRecommendations(p, recFrom, lastDay);
            } catch (Exception e) {
                // Виджет вторичен: главная не должна падать из-за движка цен.
                log.warn("Recommendations unavailable for property {}: {}", p.getId(), e.getMessage());
                continue;
            }
            for (PricingRecommendation rec : recs) {
                if (rec.recommendedPrice() == null || rec.recommendedPrice().signum() <= 0) continue;
                if (byDate.containsKey(rec.date())) continue;
                BigDecimal userPrice = prices.get(new DateUnitKey(ut.getId(), rec.date()));
                if (userPrice == null) continue;
                userTotal = userTotal.add(userPrice);
                recommendedTotal = recommendedTotal.add(rec.recommendedPrice());
                nights++;
            }
        }
        if (nights == 0) return null;
        return new Optimization(nights, userTotal, recommendedTotal);
    }

    private static int capacityOf(UnitType ut) {
        return ut.getUnitCount() == null ? 1 : Math.max(1, ut.getUnitCount());
    }

    public record DayRevenue(LocalDate date, BigDecimal actual, BigDecimal estimated, boolean past) {
        public BigDecimal total() {
            return actual.add(estimated);
        }
    }

    /**
     * @param nights сколько свободных ночей сравнили
     */
    public record Optimization(int nights, BigDecimal userTotal, BigDecimal recommendedTotal) {
        public BigDecimal uplift() {
            return recommendedTotal.subtract(userTotal);
        }
    }

    /**
     * @param actual           выручка по суммам броней, приходящаяся на ночи месяца
     * @param estimated        оценка по плановой цене для занятых ночей без суммы
     * @param stays            брони и блокировки-продажи с заездом в этом месяце
     * @param freeFutureNights свободные ночи с сегодняшнего дня до конца месяца
     * @param freeFutureValue  их стоимость по текущим ценам хоста
     * @param optimization     null, если сравнивать не с чем (месяц прошёл, нет рекомендаций)
     */
    public record MonthForecast(BigDecimal actual, BigDecimal estimated, double occupancyPct,
                                long busyNights, long capacityNights, int stays,
                                long freeFutureNights, BigDecimal freeFutureValue,
                                List<DayRevenue> daily, Optimization optimization) {

        public BigDecimal total() {
            return actual.add(estimated);
        }

        MonthForecast withOptimization(Optimization o) {
            return new MonthForecast(actual, estimated, occupancyPct, busyNights, capacityNights,
                    stays, freeFutureNights, freeFutureValue, daily, o);
        }
    }
}
