package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.WidgetQrLink;

import java.util.List;
import java.util.Optional;

public interface WidgetQrLinkRepository extends JpaRepository<WidgetQrLink, Long> {

    /** Публичный доступ по короткому коду — без tenant-фильтра: код и есть ключ. */
    Optional<WidgetQrLink> findByCode(String code);

    boolean existsByCode(String code);

    /** Действующая ссылка вида kind у виджета. */
    Optional<WidgetQrLink> findFirstByWidgetIdAndKindAndRevokedAtIsNullOrderByIdDesc(Long widgetId, String kind);

    List<WidgetQrLink> findByWidgetIdAndRevokedAtIsNull(Long widgetId);
}
