package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.BookingWidget;

import java.util.List;
import java.util.Optional;

public interface BookingWidgetRepository extends JpaRepository<BookingWidget, Long> {

    List<BookingWidget> findByTenantIdAndActiveTrueOrderByCreatedAtAsc(Long tenantId);

    Optional<BookingWidget> findByIdAndTenantIdAndActiveTrue(Long id, Long tenantId);

    /** Публичный доступ по секрету — единственный запрос без tenant-фильтра. */
    Optional<BookingWidget> findBySecretAndActiveTrue(String secret);
}
