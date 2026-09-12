package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.rentoptima.entity.CompetitorSearch;
import java.util.List;

public interface CompetitorSearchRepository extends JpaRepository<CompetitorSearch, Long> {

    List<CompetitorSearch> findByTenantIdAndActiveTrue(Long tenantId);

    @Query("SELECT cs FROM CompetitorSearch cs WHERE cs.active = true")
    List<CompetitorSearch> findAllActive();

    @Query("""
        SELECT cs FROM CompetitorSearch cs
        WHERE cs.active = true
          AND (cs.lastScrapedAt IS NULL
               OR cs.lastScrapedAt < CURRENT_TIMESTAMP - cs.scrapeIntervalHours * INTERVAL '1 hour')
    """)
    List<CompetitorSearch> findDueForScraping();
}
