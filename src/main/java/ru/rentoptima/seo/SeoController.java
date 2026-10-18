package ru.rentoptima.seo;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import ru.rentoptima.repository.BookingWidgetRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** robots.txt, sitemap.xml и файл ключа IndexNow. Всё собирается на лету, по окружению. */
@Controller
@RequiredArgsConstructor
public class SeoController {

    /** Параметры, которые не меняют содержимое страницы: Яндекс склеит такие адреса в один. */
    static final List<String> CLEAN_PARAMS = List.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term",
            "promo", "checkin", "checkout", "guests", "adults", "children", "pets", "lang",
            "yclid", "gclid");

    /** Всё, что за входом, служебное и с данными гостей. Правило — префикс адреса. */
    static final List<String> DISALLOW = List.of(
            "/api/", "/q/", "/f/", "/b/*/embed", "/widget/", "/feedback", "/ical/", "/housekeeper/",
            "/login", "/register", "/logout", "/welcome", "/error", "/forgot", "/reset", "/account/",
            "/dashboard", "/calendar", "/bookings/", "/settings", "/pricing", "/expenses",
            "/staff", "/chat", "/import", "/admin/");

    private static final MediaType TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final MediaType XML = new MediaType("application", "xml", StandardCharsets.UTF_8);

    private final SiteSettings site;
    private final BookingWidgetRepository widgetRepo;
    private final ru.rentoptima.guide.GuideRepository guides;

    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots() {
        return ResponseEntity.ok().contentType(TEXT)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(robotsTxt(site));
    }

    static String robotsTxt(SiteSettings site) {
        if (!site.isProduction()) {
            return "# " + site.getEnvironment() + ": не для поиска\nUser-agent: *\nDisallow: /\n";
        }
        StringBuilder sb = new StringBuilder("User-agent: *\n");
        DISALLOW.forEach(p -> sb.append("Disallow: ").append(p).append('\n'));
        sb.append("Clean-param: ").append(String.join("&", CLEAN_PARAMS)).append('\n');
        sb.append('\n').append("Sitemap: ").append(site.getBaseUrl()).append("/sitemap.xml\n");
        return sb.toString();
    }

    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemap() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        // Вне prod карта пустая: индексировать там нечего
        if (site.isProduction()) {
            for (SitePages.Page p : SitePages.indexed()) {
                url(sb, p.path(), p.lastmod().toString());
            }
            // Инструкции и статьи: опубликованные и не скрытые из поиска
            for (ru.rentoptima.guide.GuideRepository.Guide g : guides.published()) {
                if (g.indexable()) url(sb, g.path(), SitePages.guidePage(g).lastmod().toString());
            }
            for (BookingWidgetRepository.SitemapEntry w : widgetRepo.findForSitemap()) {
                url(sb, "/b/" + w.getSlug(), w.getUpdatedAt().toLocalDate().toString());
            }
        }
        sb.append("</urlset>\n");
        return ResponseEntity.ok().contentType(XML)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(sb.toString());
    }

    private void url(StringBuilder sb, String path, String lastmod) {
        // Адреса состоят из латиницы, цифр, дефиса и слэша — экранировать в XML нечего
        sb.append("  <url><loc>").append(site.getBaseUrl()).append(path).append("</loc>")
                .append("<lastmod>").append(lastmod).append("</lastmod></url>\n");
    }

    /** Файл ключа IndexNow: {@code /<ключ>.txt} с самим ключом внутри. Любое другое имя — 404. */
    @GetMapping("/{name:[A-Za-z0-9-]{8,128}}.txt")
    public ResponseEntity<String> indexNowKey(@PathVariable String name) {
        String key = site.getIndexNowKey();
        if (!IndexNowService.validKey(key) || !key.equals(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok().contentType(TEXT).body(key);
    }
}
