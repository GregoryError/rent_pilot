package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.AlertEvent;

import java.util.List;

public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    /** Открытые алерты tenant'а — для сверки с текущим состоянием. */
    List<AlertEvent> findByTenantIdAndResolvedAtIsNull(Long tenantId);

    /** Журнал для страницы интеграций. */
    List<AlertEvent> findTop20ByTenantIdOrderByFirstSeenAtDesc(Long tenantId);
}
