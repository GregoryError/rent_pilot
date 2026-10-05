package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.AlertEvent;

import java.util.List;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    /** Открытые алерты tenant'а — для сверки с текущим состоянием. */
    List<AlertEvent> findByTenantIdAndResolvedAtIsNull(Long tenantId);

    /** Журнал для страницы интеграций: без типа, который в Telegram не уходит. */
    List<AlertEvent> findTop20ByTenantIdAndAlertTypeNotOrderByFirstSeenAtDesc(
            Long tenantId, AlertEvent.AlertType excluded);

    /**
     * Очистка журнала tenant'а: закрытые записи и наложения (они только для журнала).
     * Открытые конфликты и сбои каналов остаются — иначе детектор завёл бы их заново
     * и повторно отправил в Telegram.
     */
    @Modifying
    @Transactional
    @Query("""
        DELETE FROM AlertEvent a
        WHERE a.tenantId = :tenantId
          AND (a.resolvedAt IS NOT NULL OR a.alertType = ru.rentoptima.entity.AlertEvent.AlertType.OVERLAP)
    """)
    int deleteClearable(@Param("tenantId") Long tenantId);

    /** Полный журнал для диагностики каналов. */
    List<AlertEvent> findTop50ByTenantIdOrderByFirstSeenAtDesc(Long tenantId);
}
