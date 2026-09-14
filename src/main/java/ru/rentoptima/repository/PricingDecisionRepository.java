package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.rentoptima.entity.PricingDecision;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PricingDecisionRepository extends JpaRepository<PricingDecision, Long> {

    // Latest decision snapshot for (property, target_date) — used to check if state changed
    @Query("SELECT d FROM PricingDecision d " +
           "WHERE d.tenantId = :tenantId AND d.propertyId = :propertyId AND d.targetDate = :targetDate " +
           "ORDER BY d.decidedAt DESC LIMIT 1")
    Optional<PricingDecision> findLatest(@Param("tenantId") Long tenantId,
                                         @Param("propertyId") Long propertyId,
                                         @Param("targetDate") LocalDate targetDate);

    // All pending decisions for a target date (to mark them BOOKED when webhook comes)
    List<PricingDecision> findByTenantIdAndPropertyIdAndTargetDateAndOutcomeIsNull(
            Long tenantId, Long propertyId, LocalDate targetDate);

    // All decisions for analysis in a period
    @Query("SELECT d FROM PricingDecision d " +
           "WHERE d.tenantId = :tenantId AND d.decidedAt >= :fromTime")
    List<PricingDecision> findForAnalysis(@Param("tenantId") Long tenantId,
                                          @Param("fromTime") java.time.LocalDateTime fromTime);

    // Mark past pending decisions as MISSED (target date is in the past, no booking arrived)
    @Modifying
    @Query("UPDATE PricingDecision d SET d.outcome = 'MISSED' " +
           "WHERE d.tenantId = :tenantId AND d.outcome IS NULL AND d.targetDate < :today")
    int markMissed(@Param("tenantId") Long tenantId, @Param("today") LocalDate today);

    // Cleanup after analysis
    @Modifying
    @Query("DELETE FROM PricingDecision d WHERE d.tenantId = :tenantId")
    int deleteAllByTenant(@Param("tenantId") Long tenantId);

    long countByTenantId(Long tenantId);
}
