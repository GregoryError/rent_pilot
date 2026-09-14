package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.rentoptima.entity.CompetitorSearch;
import java.util.List;

public interface CompetitorSearchRepository extends JpaRepository<CompetitorSearch, Long> {

    List<CompetitorSearch> findByTenantIdAndActiveTrue(Long tenantId);

    @Query("SELECT cs FROM CompetitorSearch cs WHERE cs.active = true")
    List<CompetitorSearch> findAllActive();

    @Query(value = """
        SELECT * FROM competitor_searches cs
        WHERE cs.active = true
          AND (cs.last_scraped_at IS NULL
               OR cs.last_scraped_at < NOW() - (cs.scrape_interval_hours || ' hours')::interval)
    """, nativeQuery = true)
    List<CompetitorSearch> findDueForScraping();
}
