package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.UnitType;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * «Простой режим» словаря: у хозяина один действующий объект с одной категорией на одну
 * квартиру ({@code unit_count = 1}). Тогда понятие «категория номеров» в интерфейсе не
 * показывается вообще — говорим «квартира», а выбор категории подставляется сам. Категории
 * видят только те, у кого их несколько или в категории больше одной квартиры (мини-отель).
 */
@Service
@RequiredArgsConstructor
public class UnitVocabulary {

    private final JdbcTemplate jdbc;

    /**
     * Подписи категорий: у объекта одна категория на одну квартиру — только название объекта
     * («Садовая»), иначе «Объект / категория». Категории объектов, которых нет в
     * {@code propertyNames} (выключенные), пропускаются.
     */
    public static Map<Long, String> labels(Map<Long, String> propertyNames, Collection<UnitType> units) {
        Map<Long, Integer> perProperty = new HashMap<>();
        Map<Long, Integer> unitsOf = new HashMap<>();
        for (UnitType ut : units) {
            perProperty.merge(ut.getPropertyId(), 1, Integer::sum);
            unitsOf.merge(ut.getPropertyId(), ut.getUnitCount() == null ? 1 : ut.getUnitCount(), Integer::sum);
        }
        Map<Long, String> labels = new LinkedHashMap<>();
        for (UnitType ut : units) {
            String property = propertyNames.get(ut.getPropertyId());
            if (property == null) continue;
            boolean single = perProperty.get(ut.getPropertyId()) == 1 && unitsOf.get(ut.getPropertyId()) <= 1;
            labels.put(ut.getId(), single ? property : property + " / " + ut.getName());
        }
        return labels;
    }

    public boolean simple(Long tenantId) {
        if (tenantId == null) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT count(DISTINCT p.id) = 1 AND count(ut.id) = 1 AND coalesce(max(ut.unit_count), 1) = 1
                  FROM properties p
                  LEFT JOIN unit_types ut ON ut.property_id = p.id AND ut.active
                 WHERE p.tenant_id = ? AND p.active
                """, Boolean.class, tenantId));
    }
}
