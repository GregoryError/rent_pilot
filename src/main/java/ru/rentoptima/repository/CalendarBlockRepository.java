package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.rentoptima.entity.CalendarBlock;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CalendarBlockRepository extends JpaRepository<CalendarBlock, Long> {

    List<CalendarBlock> findByUnitTypeId(Long unitTypeId);

    /**
     * Ключ идемпотентности iCal-импорта.
     */
    Optional<CalendarBlock> findByChannelIdAndExternalUid(Long channelId, String externalUid);

    /**
     * Все блокировки канала, пересекающиеся с окном [from, to).
     * Используется для reconcile — снятия исчезнувших событий.
     */
    @Query("""
        SELECT b FROM CalendarBlock b
        WHERE b.channelId = :channelId
          AND b.fromDate < :to
          AND b.toDate > :from
    """)
    List<CalendarBlock> findByChannelInRange(@Param("channelId") Long channelId,
                                              @Param("from") LocalDate from,
                                              @Param("to") LocalDate to);

    /**
     * Все блокировки нескольких unit_type, пересекающиеся с окном [from, to).
     * Для AvailabilityService (шахматка, iCal-экспорт).
     */
    @Query("""
        SELECT b FROM CalendarBlock b
        WHERE b.unitTypeId IN :unitTypeIds
          AND b.fromDate < :to
          AND b.toDate > :from
    """)
    List<CalendarBlock> findByUnitTypesInRange(@Param("unitTypeIds") List<Long> unitTypeIds,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);

    /** Ручные записи категории, заведённые после указанного момента. Для диагностики каналов. */
    List<CalendarBlock> findByUnitTypeIdAndChannelIdIsNullAndCreatedAtAfter(
            Long unitTypeId, java.time.LocalDateTime since);

    /** Последние импортированные с канала блокировки. Для диагностики каналов. */
    List<CalendarBlock> findTop10ByChannelIdOrderByCreatedAtDesc(Long channelId);

    /** Удаляет всё, что импортировано с канала. Вызывается при удалении канала. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM CalendarBlock b WHERE b.channelId = :channelId")
    int deleteByChannel(@Param("channelId") Long channelId);

    /** Блокировки, оставшиеся от уже удалённых каналов tenant'а. */
    @Query("""
        SELECT COUNT(b) FROM CalendarBlock b
        WHERE b.tenantId = :tenantId
          AND b.channelId IN (SELECT c.id FROM Channel c
                              WHERE c.tenantId = :tenantId AND c.active = false)
    """)
    long countOrphaned(@Param("tenantId") Long tenantId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("""
        DELETE FROM CalendarBlock b
        WHERE b.tenantId = :tenantId
          AND b.channelId IN (SELECT c.id FROM Channel c
                              WHERE c.tenantId = :tenantId AND c.active = false)
    """)
    int deleteOrphaned(@Param("tenantId") Long tenantId);

    /** Просроченные резервы, кросс-тенант. Для WidgetBookingService. */
    List<CalendarBlock> findByExpiresAtBefore(java.time.LocalDateTime moment);

    /** Найти все пересечения для одного unit_type — для детектора конфликтов. */
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
