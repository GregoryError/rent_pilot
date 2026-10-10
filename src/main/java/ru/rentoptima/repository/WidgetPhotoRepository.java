package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.WidgetPhoto;

import java.util.List;
import java.util.Optional;

public interface WidgetPhotoRepository extends JpaRepository<WidgetPhoto, Long> {

    /** Фото виджета по порядку показа. Публичный запрос: виджет уже найден по своему адресу. */
    List<WidgetPhoto> findByWidgetIdOrderByPositionAscIdAsc(Long widgetId);

    Optional<WidgetPhoto> findByIdAndWidgetIdAndTenantId(Long id, Long widgetId, Long tenantId);

    long countByWidgetId(Long widgetId);
}
