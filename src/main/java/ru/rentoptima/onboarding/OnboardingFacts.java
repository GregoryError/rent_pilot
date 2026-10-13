package ru.rentoptima.onboarding;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * Что у хозяина уже есть — для чек-листа «Первые шаги» и подсказок. Один запрос на все
 * пункты; всё — в пределах своего тенанта.
 */
@Service
@RequiredArgsConstructor
public class OnboardingFacts {

    /**
     * Обращение за нашей ссылкой считается обращением площадки, если это не обычный браузер:
     * хозяин мог открыть ссылку сам, посмотреть. Если же «браузер» приходил трижды и больше —
     * это площадка, которая им представляется.
     */
    static final String PLATFORM_FETCH = """
            (f.user_agent IS NULL OR f.user_agent NOT ILIKE 'Mozilla/%'
             OR (SELECT COUNT(*) FROM channel_feed_fetches g WHERE g.channel_id = f.channel_id) >= 3)
            """;

    public record Facts(boolean property, boolean platform, boolean fetched, boolean booking, Long widgetId) {
        public boolean page() {
            return widgetId != null;
        }
    }

    private final JdbcTemplate jdbc;

    public Facts of(Long tenantId) {
        return jdbc.queryForObject("""
                SELECT
                  EXISTS (SELECT 1 FROM unit_types u WHERE u.tenant_id = ? AND u.active) AS property,
                  EXISTS (SELECT 1 FROM channels c WHERE c.tenant_id = ? AND c.active AND c.channel_type = 'ICAL'
                            AND COALESCE(c.config_json->>'import_url', '') <> '') AS platform,
                  EXISTS (SELECT 1 FROM channel_feed_fetches f WHERE f.tenant_id = ? AND """ + PLATFORM_FETCH + """
                         ) AS fetched,
                  EXISTS (SELECT 1 FROM calendar_blocks b JOIN channels c ON c.id = b.channel_id
                           WHERE b.tenant_id = ? AND c.channel_type = 'ICAL' AND b.cancelled_at IS NULL
                             AND NOT b.ignored) AS booking,
                  (SELECT MIN(w.id) FROM booking_widgets w WHERE w.tenant_id = ? AND w.active) AS widget_id
                """,
                (rs, n) -> new Facts(rs.getBoolean("property"), rs.getBoolean("platform"), rs.getBoolean("fetched"),
                        rs.getBoolean("booking"), (Long) rs.getObject("widget_id")),
                tenantId, tenantId, tenantId, tenantId, tenantId);
    }

    /** Площадка, которая не забирала ссылку. */
    public record Stale(String name, String platform) {}

    /**
     * Площадка, заведённая больше суток назад и ни разу не забравшая нашу ссылку, — скорее
     * всего, ссылку не вставили в кабинет. Самая давняя из таких; нет таких — null.
     */
    public Stale staleChannel(Long tenantId) {
        return jdbc.query("""
                SELECT c.name, c.config_json->>'platform' AS platform FROM channels c
                 WHERE c.tenant_id = ? AND c.active AND c.channel_type = 'ICAL' AND c.export_secret IS NOT NULL
                   AND c.created_at < NOW() - INTERVAL '24 hours'
                   AND NOT EXISTS (SELECT 1 FROM channel_feed_fetches f WHERE f.channel_id = c.id AND """
                + PLATFORM_FETCH + ") ORDER BY c.created_at LIMIT 1",
                rs -> rs.next() ? new Stale(rs.getString("name"), rs.getString("platform")) : null, tenantId);
    }

    /** Площадки хозяина, которые ни разу не забирали нашу ссылку. */
    public Set<Long> neverFetched(Long tenantId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT c.id FROM channels c
                 WHERE c.tenant_id = ? AND c.active AND c.channel_type = 'ICAL' AND c.export_secret IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM channel_feed_fetches f WHERE f.channel_id = c.id AND """
                + PLATFORM_FETCH + ")", Long.class, tenantId));
    }
}
