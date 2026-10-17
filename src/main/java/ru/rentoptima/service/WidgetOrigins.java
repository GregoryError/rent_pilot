package ru.rentoptima.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Сайты, на которые хозяин разрешил встраивать виджет ({@code booking_widgets.allowed_origins}).
 * Хранятся origin'ами — схема, хост и порт без пути: {@code https://example.ru}.
 * По этому списку отвечает CORS публичного API и заголовок {@code frame-ancestors}
 * страницы для iframe.
 */
public final class WidgetOrigins {

    public static final int MAX = 10;

    private static final Pattern HOST = Pattern.compile(
            "(?:[a-z0-9а-яё](?:[a-z0-9а-яё-]{0,61}[a-z0-9а-яё])?\\.)+[a-zа-яё0-9-]{2,}|localhost");

    private WidgetOrigins() {}

    /**
     * Разбирает ввод хозяина: по сайту в строке, можно без схемы, с «www», с путём.
     * Непонятные строки пропускаются.
     *
     * @return нормализованный список для сохранения, по origin'у в строке
     */
    public static String normalize(String input) {
        Set<String> origins = new LinkedHashSet<>();
        if (input != null) {
            for (String line : input.split("[\\s,;]+")) {
                String origin = toOrigin(line);
                if (origin != null && origins.size() < MAX) origins.add(origin);
            }
        }
        return String.join("\n", origins);
    }

    /** Список origin'ов из сохранённого значения. */
    public static List<String> parse(String stored) {
        List<String> origins = new ArrayList<>();
        if (stored == null) return origins;
        for (String line : stored.split("\\R")) {
            if (!line.isBlank()) origins.add(line.trim());
        }
        return origins;
    }

    /** Значение директивы frame-ancestors: наш домен и разрешённые сайты. */
    public static String frameAncestors(String stored) {
        StringBuilder sb = new StringBuilder("frame-ancestors 'self'");
        for (String origin : parse(stored)) sb.append(' ').append(origin);
        return sb.toString();
    }

    static String toOrigin(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 300) return null;
        if (!s.contains("://")) s = "https://" + s;
        try {
            URI uri = URI.create(s);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null) {
                // URI не разбирает кириллические домены — достаём хост вручную
                String rest = s.substring(s.indexOf("://") + 3);
                host = rest.split("[/:?#]", 2)[0];
            }
            if (scheme == null || !(scheme.equals("https") || scheme.equals("http"))) return null;
            if (!HOST.matcher(host).matches()) return null;
            // http допускаем только для локальной отладки сайта хозяина
            if (scheme.equals("http") && !host.equals("localhost")) scheme = "https";
            int port = uri.getPort();
            return scheme + "://" + host + (port > 0 ? ":" + port : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
