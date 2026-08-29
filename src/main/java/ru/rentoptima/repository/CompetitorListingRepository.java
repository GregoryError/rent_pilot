package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.rentoptima.entity.CompetitorListing;
import java.util.List;

public interface CompetitorListingRepository extends JpaRepository<CompetitorListing, Long> {

    List<CompetitorListing> findByTenantIdAndActiveTrue(Long tenantId);

    @Query("SELECT cl FROM CompetitorListing cl WHERE cl.active = true")
    List<CompetitorListing> findAllActive();
}
