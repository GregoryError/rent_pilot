package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.rentoptima.entity.ManualOverride;

import java.time.LocalDateTime;
import java.util.List;

public interface ManualOverrideRepository extends JpaRepository<ManualOverride, Long> {

    @Query("""
        SELECT o FROM ManualOverride o
        WHERE o.tenantId = :tenantId
          AND o.active = true
          AND (o.expiresAt IS NULL OR o.expiresAt > :now)
          AND (o.propertyId IS NULL OR o.propertyId = :propertyId)
        ORDER BY o.createdAt DESC
    """)
    List<ManualOverride> findActiveForProperty(
            @Param("tenantId") Long tenantId,
            @Param("propertyId") Long propertyId,
            @Param("now") LocalDateTime now);

    @Query("""
        SELECT o FROM ManualOverride o
        WHERE o.tenantId = :tenantId
          AND o.active = true
          AND (o.expiresAt IS NULL OR o.expiresAt > :now)
        ORDER BY o.createdAt DESC
    """)
    List<ManualOverride> findAllActive(
            @Param("tenantId") Long tenantId,
            @Param("now") LocalDateTime now);
}
