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

    /** Адрес любого виджета, включая удалённые: отличить «удалена» (410) от «не было» (404). */
    Optional<BookingWidget> findBySlug(String slug);

    /** Только адрес и дата правки — больше карте сайта ничего не нужно. */
    interface SitemapEntry {
        String getSlug();
        java.time.LocalDateTime getUpdatedAt();
    }

    /**
     * Страницы для sitemap.xml — по всем хозяевам сразу, без tenant-фильтра: это публичные
     * адреса, которые хозяева сами разрешили показывать в поиске. Наружу уходят только
     * адрес и дата.
     */
    @org.springframework.data.jpa.repository.Query(
            "select w.slug as slug, w.updatedAt as updatedAt from BookingWidget w"
                    + " where w.active = true and w.searchIndexed = true order by w.id")
    List<SitemapEntry> findForSitemap();

    /** Занят ли адрес: проверяется по всем виджетам, включая удалённые — адрес уникален в таблице. */
    boolean existsBySlug(String slug);
}
