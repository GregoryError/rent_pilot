package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.rentoptima.entity.PricingInsight;

import java.util.List;
import java.util.Optional;

@Repository
public interface PricingInsightRepository extends JpaRepository<PricingInsight, Long> {

    List<PricingInsight> findByTenantIdOrderByDiscoveredAtDesc(Long tenantId);

    Optional<PricingInsight> findFirstByTenantIdAndPatternKeyOrderByDiscoveredAtDesc(
            Long tenantId, String patternKey);

    @Modifying
    @Query("DELETE FROM PricingInsight i WHERE i.tenantId = :tenantId")
    int deleteAllByTenant(@Param("tenantId") Long tenantId);

    long countByTenantId(Long tenantId);
}
