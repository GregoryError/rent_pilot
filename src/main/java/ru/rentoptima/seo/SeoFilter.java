package ru.rentoptima.seo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Индексация и единый вид адресов. Стоит раньше Spring Security, поэтому заголовок
 * получают и редиректы на вход, и ответы 401/403.
 * <ul>
 *   <li>Вне prod — {@code X-Robots-Tag: noindex, nofollow} на ВСЕХ ответах, включая
 *       статику: staging не должен попасть в поиск ни одной страницей.</li>
 *   <li>На prod — на всём, кроме страниц из {@link SitePages}, правовых страниц и
 *       {@code /b/<slug>} (там решает хозяин, заголовок ставит контроллер) и статики.</li>
 *   <li>Адрес со слэшем на конце — 301 на адрес без него.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class SeoFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Robots-Tag";
    public static final String NOINDEX = "noindex, nofollow";

    /** Файлы, а не страницы: закрывать их незачем, а картинки /media/ в поиске по картинкам полезны. */
    private static final List<String> ASSET_PREFIXES = List.of(
            "/css/", "/js/", "/fonts/", "/media/", "/libs/", "/img/", "/icons/", "/webjars/");
    private static final List<String> ASSET_FILES = List.of(
            "/w.js", "/widget.js", "/favicon.ico", "/favicon.svg", "/apple-touch-icon.png",
            "/site.webmanifest", "/robots.txt", "/sitemap.xml");

    private final SiteSettings site;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());

        if (path.length() > 1 && path.endsWith("/") && isRead(request)) {
            String query = request.getQueryString();
            String target = request.getContextPath() + path.replaceAll("/+$", "");
            if (target.isEmpty()) target = "/";
            if (!site.isProduction()) response.setHeader(HEADER, NOINDEX);
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader(HttpHeaders.LOCATION, target + (query == null ? "" : "?" + query));
            return;
        }

        if (!site.isProduction() || !mayBeIndexed(path)) {
            response.setHeader(HEADER, NOINDEX);
        }
        chain.doFilter(request, response);
    }

    private static boolean isRead(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    }

    /** Страницы, которые на prod не закрываются этим фильтром. */
    static boolean mayBeIndexed(String path) {
        if (ASSET_FILES.contains(path)) return true;
        for (String prefix : ASSET_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        if (path.equals("/") || SitePages.contains(path)) return true;
        if (path.startsWith("/legal/")) return true;
        // /b/<slug> — да (галочку хозяина проверяет WidgetPublicController), /b/<slug>/embed — нет
        return path.startsWith("/b/") && path.indexOf('/', 3) < 0;
    }
}
