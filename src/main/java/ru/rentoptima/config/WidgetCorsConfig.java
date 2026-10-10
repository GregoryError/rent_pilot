package ru.rentoptima.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.service.WidgetOrigins;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CORS публичного API виджета: запрос с чужого сайта проходит, только если хозяин
 * добавил этот сайт в разрешённые у своего виджета ({@code booking_widgets.allowed_origins}).
 * <p>
 * Раньше API был открыт любому origin. Теперь браузер на чужой странице не получит
 * ни цены, ни занятость и не отправит заявку; запрос с неразрешённого origin
 * отклоняется на сервере (403), а не только скрывается от скрипта. Запросы с нашего
 * домена (страница бронирования, iframe) не кросс-доменные и сюда не попадают.
 * <p>
 * Это защита от встраивания на посторонние сайты, а не от скриптов вне браузера —
 * от них лимит запросов, honeypot и проверка времени заполнения формы.
 */
@Configuration
@RequiredArgsConstructor
public class WidgetCorsConfig {

    /** /api/widget/{slug}/… — новый API; /widget/{secret}/… — API прежнего виджета. */
    private static final Pattern WIDGET_PATH = Pattern.compile("^/(api/widget|widget)/([^/]+)/.+");
    private static final long CACHE_MILLIS = 60_000;
    private static final int CACHE_MAX = 5_000;

    private final BookingWidgetRepository widgetRepo;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return this::configFor;
    }

    CorsConfiguration configFor(HttpServletRequest request) {
        // Без Origin запрос не кросс-доменный — в базу не ходим
        if (request.getHeader(HttpHeaders.ORIGIN) == null) return null;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Шрифты виджета — общедоступные файлы: браузер требует CORS для шрифта с чужого
        // домена, а виджет на сайте хозяина именно так их и берёт.
        if (path.startsWith("/fonts/")) {
            CorsConfiguration fonts = new CorsConfiguration();
            fonts.addAllowedOrigin("*");
            fonts.setAllowedMethods(List.of("GET"));
            fonts.setMaxAge(86400L);
            return fonts;
        }
        Matcher m = WIDGET_PATH.matcher(path);
        if (!m.matches()) return null;

        CorsConfiguration config = new CorsConfiguration();
        // Пустой список — ни один чужой origin не подойдёт
        config.setAllowedOrigins(origins("api/widget".equals(m.group(1)), m.group(2)));
        config.setAllowedMethods(List.of("GET", "POST"));
        config.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE));
        config.setExposedHeaders(List.of(HttpHeaders.ETAG));
        config.setAllowCredentials(false);
        config.setMaxAge(600L);
        return config;
    }

    private List<String> origins(boolean bySlug, String key) {
        if (key.length() > 80) return List.of();
        String cacheKey = (bySlug ? "s:" : "k:") + key;
        long now = System.currentTimeMillis();
        Cached cached = cache.get(cacheKey);
        if (cached != null && now - cached.at < CACHE_MILLIS) return cached.origins;

        Optional<BookingWidget> widget = bySlug
                ? widgetRepo.findBySlugAndActiveTrue(key) : widgetRepo.findBySecretAndActiveTrue(key);
        List<String> origins = widget.map(w -> WidgetOrigins.parse(w.getAllowedOrigins())).orElse(List.of());
        if (cache.size() > CACHE_MAX) cache.clear();
        cache.put(cacheKey, new Cached(origins, now));
        return origins;
    }

    /** Хозяин поменял список сайтов — не ждём минуту, пока кэш истечёт. */
    public void evict() {
        cache.clear();
    }

    private record Cached(List<String> origins, long at) {}
}
