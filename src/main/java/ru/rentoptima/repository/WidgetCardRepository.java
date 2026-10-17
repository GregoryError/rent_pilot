package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.WidgetCard;

import java.util.Optional;

public interface WidgetCardRepository extends JpaRepository<WidgetCard, Long> {

    Optional<WidgetCard> findByWidgetIdAndTenantId(Long widgetId, Long tenantId);
}
