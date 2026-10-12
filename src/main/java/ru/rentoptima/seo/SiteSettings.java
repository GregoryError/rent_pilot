package ru.rentoptima.seo;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Настройки сайта, зависящие от окружения: где мы запущены и можно ли это индексировать.
 * <p>
 * Индексируется только prod ({@code APP_ENVIRONMENT=prod}). Всё остальное — staging,
 * partner, локальный запуск и запуск без переменной — закрыто целиком: забыть переменную
 * безопаснее, чем случайно отдать поисковику копию сайта.
 */
@Getter
@Component
public class SiteSettings {

    private final String environment;
    private final String baseUrl;
    private final String yandexVerification;
    private final String googleVerification;
    private final String indexNowKey;

    public SiteSettings(@Value("${app.environment:dev}") String environment,
                        @Value("${app.site.base-url:https://optirent.ru}") String baseUrl,
                        @Value("${app.site.yandex-verification:}") String yandexVerification,
                        @Value("${app.site.google-verification:}") String googleVerification,
                        @Value("${app.site.indexnow-key:}") String indexNowKey) {
        this.environment = environment == null ? "" : environment.trim().toLowerCase();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.yandexVerification = yandexVerification.trim();
        this.googleVerification = googleVerification.trim();
        this.indexNowKey = indexNowKey.trim();
    }

    public boolean isProduction() {
        return "prod".equals(environment) || "production".equals(environment);
    }

    /**
     * Канонический адрес страницы — без параметров. На prod домен берётся из настройки,
     * а не из запроса: каноническим должен быть https://optirent.ru, каким бы путём
     * (www, http, IP) запрос ни пришёл. Вне prod — адрес самого запроса.
     */
    public String canonical(HttpServletRequest request, String path) {
        String base = isProduction() ? baseUrl
                : ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        return base + path;
    }
}
