package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.rentoptima.entity.CompetitorPrice;
import ru.rentoptima.service.CompetitorService.CompetitorPriceView;

import java.math.BigDecimal;
import java.util.List;

public interface CompetitorPriceRepository extends JpaRepository<CompetitorPrice, Long> {

    @Query("""
        SELECT new ru.rentoptima.service.CompetitorService$CompetitorPriceView(
            cl.competitorName, cl.platform, cp.price, cp.scrapedAt
        )
        FROM CompetitorPrice cp
        JOIN CompetitorListing cl ON cp.listingId = cl.id
        WHERE cl.tenantId = :tenantId AND cl.active = true
          AND cp.scrapedAt = (
              SELECT MAX(cp2.scrapedAt) FROM CompetitorPrice cp2 WHERE cp2.listingId = cp.listingId
          )
        ORDER BY cl.competitorName
    """)
    List<CompetitorPriceView> findLatestByTenant(Long tenantId);

    @Query("""
        SELECT AVG(cp.price)
        FROM CompetitorPrice cp
        JOIN CompetitorListing cl ON cp.listingId = cl.id
        WHERE cl.tenantId = :tenantId AND cl.active = true
          AND cp.scrapedAt = (
              SELECT MAX(cp2.scrapedAt) FROM CompetitorPrice cp2 WHERE cp2.listingId = cp.listingId
          )
    """)
    BigDecimal avgLatestPrice(Long tenantId);

    List<CompetitorPrice> findByListingIdOrderByScrapedAtDesc(Long listingId);
}
