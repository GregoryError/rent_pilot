package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.rentoptima.entity.CompetitorDailyPrice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface CompetitorDailyPriceRepository extends JpaRepository<CompetitorDailyPrice, Long> {

    /**
     * Latest prices per competitor per date within a range.
     * Returns only the most recent scrape for each competitor+date pair.
     */
    @Query("""
        SELECT cdp FROM CompetitorDailyPrice cdp
        WHERE cdp.tenantId = :tenantId
          AND cdp.date BETWEEN :fromDate AND :toDate
          AND cdp.scrapedAt = (
              SELECT MAX(cdp2.scrapedAt) FROM CompetitorDailyPrice cdp2
              WHERE cdp2.tenantId = cdp.tenantId
                AND cdp2.competitorName = cdp.competitorName
                AND cdp2.platform = cdp.platform
                AND cdp2.date = cdp.date
          )
        ORDER BY cdp.date, cdp.competitorName
    """)
    List<CompetitorDailyPrice> findLatestByTenantAndDateRange(
            Long tenantId, LocalDate fromDate, LocalDate toDate);

    /**
     * Average competitor price per date for a tenant.
     */
    @Query("""
        SELECT cdp.date, AVG(cdp.price)
        FROM CompetitorDailyPrice cdp
        WHERE cdp.tenantId = :tenantId
          AND cdp.date BETWEEN :fromDate AND :toDate
          AND cdp.scrapedAt > :since
        GROUP BY cdp.date
        ORDER BY cdp.date
    """)
    List<Object[]> avgPriceByDate(Long tenantId, LocalDate fromDate, LocalDate toDate, LocalDateTime since);

    /**
     * Count distinct competitors with prices in a date range.
     */
    @Query("""
        SELECT COUNT(DISTINCT cdp.competitorName)
        FROM CompetitorDailyPrice cdp
        WHERE cdp.tenantId = :tenantId
          AND cdp.date BETWEEN :fromDate AND :toDate
          AND cdp.scrapedAt > :since
    """)
    long countDistinctCompetitors(Long tenantId, LocalDate fromDate, LocalDate toDate, LocalDateTime since);

    /**
     * Удалить старые данные (старше N дней) для экономии места.
     */
    @Query("DELETE FROM CompetitorDailyPrice cdp WHERE cdp.scrapedAt < :before")
    void deleteOlderThan(LocalDateTime before);
}
