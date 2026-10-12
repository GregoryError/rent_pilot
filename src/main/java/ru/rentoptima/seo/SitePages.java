package ru.rentoptima.seo;

import java.time.LocalDate;
import java.util.List;

/**
 * Публичные страницы сайта, которые можно индексировать: по этому списку строится
 * sitemap.xml и снимается {@code X-Robots-Tag: noindex}. Страница, которой здесь нет,
 * в поиск не попадёт.
 * <p>
 * {@code lastmod} — дата последней содержательной правки текста страницы. Меняется руками
 * вместе с текстом: поисковики перестают верить lastmod, если он обновляется без причины.
 */
public final class SitePages {

    public record Page(String path, LocalDate lastmod) {}

    /** Посадочные страницы появляются здесь вместе со своими шаблонами. */
    public static final List<Page> PAGES = List.of();

    private SitePages() {}

    public static boolean contains(String path) {
        return PAGES.stream().anyMatch(p -> p.path().equals(path));
    }
}
