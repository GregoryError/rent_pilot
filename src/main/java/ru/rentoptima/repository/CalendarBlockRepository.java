package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.rentoptima.entity.CalendarBlock;

import java.time.LocalDate;
import java.util.List;

public interface CalendarBlockRepository extends JpaRepository<CalendarBlock, Long> {

    List<CalendarBlock> findByUnitTypeId(Long unitTypeId);

    /**
     * Найти все блокировки пересекающиеся с диапазоном [from, to).
     * Полуоткрытый интервал — to это checkout date, не включительно.
     */
    @Query("""
        SELECT b FROM CalendarBlock b
        WHERE b.unitTypeId = :unitTypeId
          AND b.fromDate < :to
          AND b.toDate > :from
    """)
    List<CalendarBlock> findOverlapping(@Param("unitTypeId") Long unitTypeId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);
}
