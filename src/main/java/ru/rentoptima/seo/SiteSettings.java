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
    /** Счётчик Яндекс.Метрики сайта (не хозяев): только цифры, пусто — Метрики на сайте нет. */
    private final String metrikaId;

    public SiteSettings(@Value("${app.environment:dev}") String environment,
                        @Value("${app.site.base-url:https://optirent.ru}") String baseUrl,
                        @Value("${app.site.yandex-verification:}") String yandexVerification,
                        @Value("${app.site.google-verification:}") String googleVerification,
                        @Value("${app.site.indexnow-key:}") String indexNowKey,
                        @Value("${app.site.metrika-id:}") String metrikaId) {
        this.environment = environment == null ? "" : environment.trim().toLowerCase();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.yandexVerification = yandexVerification.trim();
        this.googleVerification = googleVerification.trim();
        this.indexNowKey = indexNowKey.trim();
        String digits = metrikaId == null ? "" : metrikaId.trim();
        this.metrikaId = digits.matches("\\d{1,12}") ? digits : "";
    }

    public boolean isProduction() {
        return "prod".equals(environment) || "production".equals(environment);
    }

    /**
     * Начало любого адреса, который мы отдаём наружу: письма, Telegram, ссылки фидов,
     * QR и короткие ссылки, ссылки горничной и отзывов, код вставки виджета, canonical.
     * Других источников адреса в коде нет — не собирать его из запроса напрямую.
     * <p>
     * На prod — только {@code APP_BASE_URL}, с какого бы домена ни пришёл запрос (после
     * переезда staging.optirent.ru ещё обслуживает /ical/, /q/ и /f/). Вне prod — адрес
     * запроса (ссылка с partner ведёт на partner), но только если хост — наш домен, его
     * поддомен или localhost: иначе подменой заголовка Host можно было бы получить письмо
     * со ссылкой на чужой сайт. Без запроса (планировщики) — {@link #getBaseUrl()}.
     */
    public String linkBase(HttpServletRequest request) {
        if (isProduction()) return baseUrl;
        String host = request.getServerName() == null ? "" : request.getServerName().toLowerCase();
        String own = java.net.URI.create(baseUrl).getHost();
        boolean trusted = host.equals(own) || (own != null && host.endsWith("." + own))
                || host.equals("localhost") || host.equals("127.0.0.1");
        return trusted ? ServletUriComponentsBuilder.fromContextPath(request).build().toUriString() : baseUrl;
    }

    /**
     * Канонический адрес страницы — без параметров. На prod домен берётся из настройки,
     * а не из запроса: каноническим должен быть https://optirent.ru, каким бы путём
     * (www, http, IP) запрос ни пришёл. Вне prod — адрес самого запроса, как в {@link #linkBase}.
     */
    public String canonical(HttpServletRequest request, String path) {
        return linkBase(request) + path;
    }

    /**
     * На prod запрос пришёл не на домен из {@code APP_BASE_URL} — например, на прежний
     * staging.optirent.ru, который nginx пропускает только для /ical/, /q/ и /f/.
     * Страницу на таком домене не показываем: её стили и запросы ушли бы на основной домен.
     */
    public boolean isSecondaryHost(HttpServletRequest request) {
        if (!isProduction()) return false;
        String host = request.getServerName();
        String own = java.net.URI.create(baseUrl).getHost();
        return host != null && own != null && !host.equalsIgnoreCase(own);
    }
}
