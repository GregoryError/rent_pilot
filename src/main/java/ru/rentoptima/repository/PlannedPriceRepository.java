package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.PlannedPrice;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlannedPriceRepository extends JpaRepository<PlannedPrice, Long> {

    Optional<PlannedPrice> findByUnitTypeIdAndDate(Long unitTypeId, LocalDate date);

    List<PlannedPrice> findByUnitTypeIdInAndDateBetween(
            List<Long> unitTypeIds, LocalDate from, LocalDate toInclusive);
}
