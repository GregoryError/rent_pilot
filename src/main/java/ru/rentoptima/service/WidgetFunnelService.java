package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.BookingWidget;

import java.net.URI;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Воронка виджета бронирования: приём событий и агрегаты для страницы статистики.
 * <p>
 * Событие — «в виджете произошёл шаг»: счётчик {@code (виджет, день, шаг, источник)}
 * увеличивается на единицу. Посетители не различаются — нет ни cookie, ни IP, — поэтому
 * числа означают открытия виджета, а не уникальных людей: один гость, зашедший трижды,
 * — три просмотра.
 */
@Service
@RequiredArgsConstructor
public class WidgetFunnelService {

    /** Шаги воронки по порядку. */
    public static final List<String> STEPS = List.of("view", "dates", "form", "submit", "success");
    public static final String DIRECT = "direct";
    public static final String OTHER = "other";
    /** Больше стольких разных источников в день на виджет не заводим: остальное — «other». */
    static final int MAX_SOURCES_PER_DAY = 50;

    private final JdbcTemplate jdbc;

    /**
     * @return false, если шаг неизвестен — событие не записано
     */
    public boolean record(BookingWidget w, String step, String utmSource, String referrer, String pageHost) {
        if (step == null || !STEPS.contains(step)) return false;
        LocalDate today = LocalDate.now();
        String source = source(utmSource, referrer, pageHost);
        if (!DIRECT.equals(source) && !known(w.getId(), today, source)
                && distinctSources(w.getId(), today) >= MAX_SOURCES_PER_DAY) {
            // Защита от раздувания таблицы выдуманными utm_source
            source = OTHER;
        }
        jdbc.update("""
                INSERT INTO widget_funnel_daily(widget_id, tenant_id, day, step, source, hits)
                VALUES (?, ?, ?, ?, ?, 1)
                ON CONFLICT (widget_id, day, step, source) DO UPDATE SET hits = widget_funnel_daily.hits + 1
                """, w.getId(), w.getTenantId(), Date.valueOf(today), step, source);
        return true;
    }

    private boolean known(Long widgetId, LocalDate day, String source) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM widget_funnel_daily WHERE widget_id = ? AND day = ? AND source = ?",
                Integer.class, widgetId, Date.valueOf(day), source);
        return n != null && n > 0;
    }

    private int distinctSources(Long widgetId, LocalDate day) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT source) FROM widget_funnel_daily WHERE widget_id = ? AND day = ?",
                Integer.class, widgetId, Date.valueOf(day));
        return n == null ? 0 : n;
    }

    /**
     * Источник перехода: метка utm_source, иначе сайт, с которого пришёл посетитель,
     * иначе «direct». Переход внутри того же сайта (referrer с тем же хостом, что и
     * страница с виджетом) источником не считается.
     */
    static String source(String utmSource, String referrer, String pageHost) {
        String utm = clean(utmSource);
        if (utm != null) return utm;
        String host = host(referrer);
        if (host == null || host.equals(clean(pageHost))) return DIRECT;
        return host;
    }

    private static String host(String url) {
        if (url == null || url.isBlank() || url.length() > 2000) return null;
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            return clean(host.startsWith("www.") ? host.substring(4) : host);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Латиница, цифры, точка, дефис, подчёркивание; до 100 символов. Остальное выбрасывается. */
    private static String clean(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        if (s.isEmpty()) return null;
        return s.length() > 100 ? s.substring(0, 100) : s;
    }

    /** Сколько раз произошёл каждый шаг за период (включительно), по порядку шагов. */
    public Map<String, Long> funnel(Long tenantId, Long widgetId, LocalDate from, LocalDate to) {
        Map<String, Long> result = new LinkedHashMap<>();
        STEPS.forEach(s -> result.put(s, 0L));
        jdbc.query("""
                SELECT step, SUM(hits) FROM widget_funnel_daily
                WHERE tenant_id = ? AND widget_id = ? AND day BETWEEN ? AND ?
                GROUP BY step
                """, rs -> {
            if (result.containsKey(rs.getString(1))) result.put(rs.getString(1), rs.getLong(2));
        }, tenantId, widgetId, Date.valueOf(from), Date.valueOf(to));
        return result;
    }

    /** Источники за период: просмотры и созданные заявки, по убыванию просмотров. */
    public List<SourceRow> sources(Long tenantId, Long widgetId, LocalDate from, LocalDate to) {
        List<SourceRow> rows = new ArrayList<>();
        jdbc.query("""
                SELECT source,
                       SUM(CASE WHEN step = 'view' THEN hits ELSE 0 END) AS views,
                       SUM(CASE WHEN step = 'success' THEN hits ELSE 0 END) AS done
                FROM widget_funnel_daily
                WHERE tenant_id = ? AND widget_id = ? AND day BETWEEN ? AND ?
                GROUP BY source
                ORDER BY views DESC, done DESC, source
                LIMIT 30
                """, rs -> {
            rows.add(new SourceRow(rs.getString(1), rs.getLong(2), rs.getLong(3)));
        }, tenantId, widgetId, Date.valueOf(from), Date.valueOf(to));
        return rows;
    }

    /** Просмотры по дням — для графика. Дни без событий в ответе есть, с нулём. */
    public Map<LocalDate, Long> viewsByDay(Long tenantId, Long widgetId, LocalDate from, LocalDate to) {
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) result.put(d, 0L);
        jdbc.query("""
                SELECT day, SUM(hits) FROM widget_funnel_daily
                WHERE tenant_id = ? AND widget_id = ? AND step = 'view' AND day BETWEEN ? AND ?
                GROUP BY day
                """, rs -> {
            result.put(rs.getDate(1).toLocalDate(), rs.getLong(2));
        }, tenantId, widgetId, Date.valueOf(from), Date.valueOf(to));
        return result;
    }

    public record SourceRow(String source, long views, long success) {}
}
