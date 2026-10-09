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
     * Для AvailabilityService (шахматка, iCal-экспорт). Блокировки, которые хост
     * открыл вручную (ignored), и удалённые ручные записи (cancelledAt) сюда не
     * попадают — занятостью они не считаются.
     */
    @Query("""
        SELECT b FROM CalendarBlock b
        WHERE b.unitTypeId IN :unitTypeIds
          AND b.ignored = false
          AND b.cancelledAt IS NULL
          AND b.fromDate < :to
          AND b.toDate > :from
    """)
    List<CalendarBlock> findByUnitTypesInRange(@Param("unitTypeIds") List<Long> unitTypeIds,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);

    /**
     * Удалённые ручные записи, пересекающиеся с окном [from, to), — только для
     * iCal-экспорта: они отдаются площадкам со STATUS:CANCELLED.
     */
    @Query("""
        SELECT b FROM CalendarBlock b
        WHERE b.unitTypeId IN :unitTypeIds
          AND b.cancelledAt IS NOT NULL
          AND b.fromDate < :to
          AND b.toDate > :from
    """)
    List<CalendarBlock> findCancelledByUnitTypesInRange(@Param("unitTypeIds") List<Long> unitTypeIds,
                                                         @Param("from") LocalDate from,
                                                         @Param("to") LocalDate to);

    /** Удалённые ручные записи, срок хранения которых истёк, кросс-тенант. Для очистки. */
    List<CalendarBlock> findByCancelledAtBefore(java.time.LocalDateTime moment);

    /**
     * Ручные записи категории, заведённые после указанного момента, включая удалённые
     * (cancelledAt). По ним импорт опознаёт эхо от каналов, которые меняют UID.
     */
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

    /**
     * Все блокировки дня, включая открытые вручную (ignored), — для модалки шахматки.
     * Удалённых ручных записей (cancelledAt) здесь нет.
     */
    @Query("""
        SELECT b FROM CalendarBlock b
        WHERE b.unitTypeId = :unitTypeId
          AND b.cancelledAt IS NULL
          AND b.fromDate < :to
          AND b.toDate > :from
    """)
    List<CalendarBlock> findOverlapping(@Param("unitTypeId") Long unitTypeId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);
}
