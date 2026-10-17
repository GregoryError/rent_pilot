package ru.rentoptima.housing;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Каким словом говорить с хозяином о его жилье. Все действующие объекты одного типа — слово этого
 * типа («Добавить квартиру», «ваш дом»); объектов нет или они разных типов — {@link Housing#GENERIC}
 * («жильё», в списках — «объекты»).
 */
@Service
@RequiredArgsConstructor
public class HousingVocabulary {

    private final JdbcTemplate jdbc;

    public Housing forTenant(Long tenantId) {
        if (tenantId == null) return Housing.GENERIC;
        List<String> kinds = jdbc.queryForList(
                "SELECT DISTINCT kind FROM properties WHERE tenant_id = ? AND active", String.class, tenantId);
        return kinds.size() == 1 ? HousingKind.of(kinds.get(0)).housing : Housing.GENERIC;
    }

    /** Слово для одного объекта — по его типу. */
    public static Housing forKind(String kind) {
        return HousingKind.of(kind).housing;
    }
}
