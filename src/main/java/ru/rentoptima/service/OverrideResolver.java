package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.ManualOverride;
import ru.rentoptima.repository.ManualOverrideRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
     * Ищет активный price_multiplier для конкретной даты.
     * Возвращает 1.0 если ни одного не найдено.
     * При нескольких — берёт самый свежий.
     */
    public double getActivePriceMultiplier(Long tenantId, Long propertyId, LocalDate date) {
        List<ManualOverride> active = overrideRepo.findActiveForProperty(
                tenantId, propertyId, LocalDateTime.now());

        for (ManualOverride o : active) {
            if (!"price_multiplier".equals(o.getOverrideType())) continue;

            Map<String, Object> p = o.getParams();
            if (p == null) continue;

            LocalDate from = parseDate(p.get("from"));
            LocalDate to = parseDate(p.get("to"));
            if (from == null || to == null) continue;

            if (!date.isBefore(from) && !date.isAfter(to)) {
                Object factorObj = p.get("factor");
                if (factorObj instanceof Number n) {
                    double factor = n.doubleValue();
                    if (factor > 0.5 && factor < 3.0) {
                        log.debug("Override multiplier {} applied for {} (override #{})",
                                factor, date, o.getId());
                        return factor;
                    }
                }
            }
        }
        return 1.0;
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
