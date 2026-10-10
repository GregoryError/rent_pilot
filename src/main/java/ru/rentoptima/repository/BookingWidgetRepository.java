package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.BookingWidget;

import java.util.List;
import java.util.Optional;

public interface BookingWidgetRepository extends JpaRepository<BookingWidget, Long> {

    List<BookingWidget> findByTenantIdAndActiveTrueOrderByCreatedAtAsc(Long tenantId);

    Optional<BookingWidget> findByIdAndTenantIdAndActiveTrue(Long id, Long tenantId);

    /** Публичный доступ по секрету (старые ссылки) — без tenant-фильтра. */
    Optional<BookingWidget> findBySecretAndActiveTrue(String secret);

    /** Публичный доступ по адресу виджета — без tenant-фильтра. */
    Optional<BookingWidget> findBySlugAndActiveTrue(String slug);

    /** Занят ли адрес: проверяется по всем виджетам, включая удалённые — адрес уникален в таблице. */
    boolean existsBySlug(String slug);
}
