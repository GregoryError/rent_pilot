package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.PlannedPrice;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.PlannedPriceRepository;
import ru.rentoptima.repository.UnitTypeRepository;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Единая точка вычисления «цены на день».
 * <p>
 * Приоритет:
 * <ol>
 *   <li>planned_prices (явный override от оператора на конкретную дату)</li>
 *   <li>unit_type.weekend_price (если дата — суббота/воскресенье и значение задано)</li>
 *   <li>unit_type.base_price</li>
 * </ol>
 * Если ни один источник не даёт значение — возвращает null. Шахматка на такой
 * цене нарисует «—», дашборд не включит день в прогноз выручки.
 * <p>
 * Выделено в отдельный сервис, чтобы и шахматка, и дашборд, и в будущем pricing-push
 * в Avito брали цену из одной точки — иначе логика расползётся по компонентам
 * и начнутся расхождения в выдаваемых значениях.
 */
@Service
@RequiredArgsConstructor
public class EffectivePriceService {

    private final PlannedPriceRepository plannedPriceRepo;
    private final UnitTypeRepository unitTypeRepo;

    /**
     * Пакетное разрешение цен для набора unit_type на диапазон дат.
     * <p>
     * Делает ровно два запроса (unit_types + planned_prices) независимо от размера
     * диапазона — важно для шахматки на 60 дней × 6 категорий, иначе получили бы
     * 360 отдельных запросов.
     *
     * @param unitTypeIds категории
     * @param from        начало диапазона, включительно
     * @param toInclusive конец диапазона, включительно
     * @return Map (unit_type_id, date) → цена; отсутствие ключа означает «цена неизвестна»
     */
    public Map<DateUnitKey, BigDecimal> resolveBatch(
            List<Long> unitTypeIds, LocalDate from, LocalDate toInclusive) {
        if (unitTypeIds.isEmpty() || toInclusive.isBefore(from)) return Map.of();

        List<UnitType> unitTypes = unitTypeRepo.findAllById(unitTypeIds);
        Map<Long, UnitType> byId = new HashMap<>();
        for (UnitType ut : unitTypes) byId.put(ut.getId(), ut);

        List<PlannedPrice> overrides = plannedPriceRepo
                .findByUnitTypeIdInAndDateBetween(unitTypeIds, from, toInclusive);
        Map<DateUnitKey, BigDecimal> result = new HashMap<>();
        for (PlannedPrice pp : overrides) {
            result.put(new DateUnitKey(pp.getUnitTypeId(), pp.getDate()), pp.getPrice());
        }

        // Дозаполняем недостающее базой или weekend_price.
        for (Long unitTypeId : unitTypeIds) {
            UnitType ut = byId.get(unitTypeId);
            if (ut == null) continue;
            LocalDate d = from;
            while (!d.isAfter(toInclusive)) {
                DateUnitKey key = new DateUnitKey(unitTypeId, d);
                if (!result.containsKey(key)) {
                    BigDecimal fallback = fallbackPrice(ut, d);
                    if (fallback != null) result.put(key, fallback);
                }
                d = d.plusDays(1);
            }
        }
        return result;
    }

    /** Разрешить цену одной (date, unit_type) без похода в БД — удобно в тестах. */
    public static BigDecimal resolve(UnitType unitType, LocalDate date, BigDecimal plannedOverride) {
        if (plannedOverride != null) return plannedOverride;
        return fallbackPrice(unitType, date);
    }

    static BigDecimal fallbackPrice(UnitType ut, LocalDate date) {
        if (ut == null) return null;
        if (isWeekend(date) && ut.getWeekendPrice() != null) {
            return ut.getWeekendPrice();
        }
        return ut.getBasePrice();
    }

    static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    public record DateUnitKey(Long unitTypeId, LocalDate date) {}
}
