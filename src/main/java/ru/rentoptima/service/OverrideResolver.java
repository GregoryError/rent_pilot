package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.ManualOverride;
import ru.rentoptima.repository.ManualOverrideRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Резолвер ручных override'ов пользователя.
 * Применяется в точках PricingEngine чтобы модифицировать финальные значения.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverrideResolver {

    private final ManualOverrideRepository overrideRepo;

    /**
     * price_multiplier: множитель для конкретной даты.
     * Возвращает 1.0 если ни одного не найдено.
     */
    public double getActivePriceMultiplier(Long tenantId, Long propertyId, LocalDate date) {
        for (ManualOverride o : findActive(tenantId, propertyId)) {
            if (!"price_multiplier".equals(o.getOverrideType())) continue;
            Map<String, Object> p = o.getParams();
            if (p == null) continue;

            if (isDateInRange(date, p)) {
                Object factorObj = p.get("factor");
                if (factorObj instanceof Number n) {
                    double factor = n.doubleValue();
                    if (factor > 0.5 && factor < 3.0) {
                        return factor;
                    }
                }
            }
        }
        return 1.0;
    }

    /**
     * min_stay_override: явное значение min_stay для даты.
     * null = использовать расчётный.
     */
    public Integer getActiveMinStay(Long tenantId, Long propertyId, LocalDate date) {
        for (ManualOverride o : findActive(tenantId, propertyId)) {
            if (!"min_stay_override".equals(o.getOverrideType())) continue;
            Map<String, Object> p = o.getParams();
            if (p == null) continue;

            if (isDateInRange(date, p)) {
                Object valObj = p.get("value");
                if (valObj instanceof Number n) {
                    int v = n.intValue();
                    if (v >= 1 && v <= 14) return v;
                }
            }
        }
        return null;
    }

    /**
     * close_dates: множество дат, которые нужно закрыть.
     * Возвращает пустой Set если не найдено.
     */
    public Set<LocalDate> getClosedDates(Long tenantId, Long propertyId) {
        Set<LocalDate> closed = new HashSet<>();
        for (ManualOverride o : findActive(tenantId, propertyId)) {
            if (!"close_dates".equals(o.getOverrideType())) continue;
            Map<String, Object> p = o.getParams();
            if (p == null) continue;

            LocalDate from = parseDate(p.get("from"));
            LocalDate to = parseDate(p.get("to"));
            if (from == null || to == null) continue;

            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                closed.add(d);
            }
        }
        return closed;
    }

    /**
     * open_ahead_days: активный override горизонта. null = использовать из настроек.
     */
    public Integer getActiveOpenAheadDays(Long tenantId, Long propertyId) {
        for (ManualOverride o : findActive(tenantId, propertyId)) {
            if (!"open_ahead_days".equals(o.getOverrideType())) continue;
            Map<String, Object> p = o.getParams();
            if (p == null) continue;

            Object valObj = p.get("days");
            if (valObj instanceof Number n) {
                int v = n.intValue();
                if (v >= 7 && v <= 365) return v;
            }
        }
        return null;
    }

    /**
     * floor_ceil: активные границы цен. null если не переопределены.
     */
    public int[] getActiveFloorCeil(Long tenantId, Long propertyId) {
        for (ManualOverride o : findActive(tenantId, propertyId)) {
            if (!"floor_ceil".equals(o.getOverrideType())) continue;
            Map<String, Object> p = o.getParams();
            if (p == null) continue;

            Object floorObj = p.get("floor");
            Object ceilObj = p.get("ceil");
            if (floorObj instanceof Number nf && ceilObj instanceof Number nc) {
                int floor = nf.intValue();
                int ceil = nc.intValue();
                if (floor > 0 && ceil > floor && ceil < 100000) {
                    return new int[]{floor, ceil};
                }
            }
        }
        return null;
    }

    private List<ManualOverride> findActive(Long tenantId, Long propertyId) {
        return overrideRepo.findActiveForProperty(tenantId, propertyId, LocalDateTime.now());
    }

    private boolean isDateInRange(LocalDate date, Map<String, Object> p) {
        LocalDate from = parseDate(p.get("from"));
        LocalDate to = parseDate(p.get("to"));
        return from != null && to != null
                && !date.isBefore(from) && !date.isAfter(to);
    }

    private LocalDate parseDate(Object v) {
        if (v == null) return null;
        try {
            return LocalDate.parse(v.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
