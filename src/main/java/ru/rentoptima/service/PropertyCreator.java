package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.UnitTypeRepository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Создание объекта и его категории — общее для страницы «Объекты» и мастера первой
 * настройки. Объект пишется через JdbcTemplate, а не JPA: у {@code Property} двойной
 * маппинг {@code tenant_id}, см. CLAUDE.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyCreator {

    private final JdbcTemplate jdbc;
    private final UnitTypeRepository unitTypeRepo;

    /** @return id созданного объекта */
    @Transactional
    public Long createProperty(Long tenantId, String name, String address, String city) {
        if (tenantId == null) throw new IllegalStateException("tenantId в сессии не установлен");
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        Long id = jdbc.queryForObject("""
                INSERT INTO properties
                    (tenant_id, name, address, city,
                     feedback_code, housekeeper_code,
                     active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                RETURNING id
                """, Long.class,
                tenantId, name, address, city, generateCode(), generateCode(), now, now);
        log.info("Property создан: tenant={}, name='{}'", tenantId, name);
        return id;
    }

    @Transactional
    public UnitType createUnitType(Long tenantId, Long propertyId, String name, Integer unitCount,
                                   BigDecimal basePrice, BigDecimal weekendPrice) {
        UnitType ut = new UnitType();
        ut.setTenantId(tenantId);
        ut.setPropertyId(propertyId);
        ut.setName(name != null ? name.trim() : "Основной");
        ut.setUnitCount(unitCount == null || unitCount < 1 ? 1 : unitCount);
        ut.setBasePrice(basePrice);
        ut.setWeekendPrice(weekendPrice);
        ut.setActive(true);
        ut.setCreatedAt(LocalDateTime.now());
        ut.setUpdatedAt(LocalDateTime.now());
        unitTypeRepo.save(ut);
        log.info("UnitType создан: tenant={}, property={}, name='{}'", tenantId, propertyId, ut.getName());
        return ut;
    }

    private static String generateCode() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
