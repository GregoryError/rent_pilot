package ru.rentoptima.onboarding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.rentoptima.service.TelegramService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Сопровождение после мастера: сообщить хозяину, что площадка впервые забрала нашу ссылку и
 * что с неё впервые пришли брони. По одному сообщению на площадку и вид.
 * <p>
 * Сообщение ложится в {@code onboarding_notices} (плашка при следующем открытии кабинета) и
 * уходит в Telegram, если он подключён. Сопровождаются только хозяева, прошедшие онбординг:
 * тем, кто создан до него, эти сообщения не приходят.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingNotifier {

    public static final String FETCHED = "FETCHED";
    public static final String BOOKINGS = "BOOKINGS";

    private final JdbcTemplate jdbc;
    private final TelegramService telegram;

    /** Площадки хозяев с онбордингом, для которых событие случилось, а сообщения ещё не было. */
    private static final String CANDIDATES = """
            SELECT c.id, c.tenant_id, c.name FROM channels c
             WHERE c.active AND c.channel_type = 'ICAL'
               AND EXISTS (SELECT 1 FROM onboarding_state o WHERE o.tenant_id = c.tenant_id
                              AND COALESCE(o.data->>'legacy', 'false') <> 'true')
               AND NOT EXISTS (SELECT 1 FROM onboarding_notices n WHERE n.channel_id = c.id AND n.kind = ?)
               AND EXISTS (%s)
            """;

    private static final String WAS_FETCHED =
            "SELECT 1 FROM channel_feed_fetches f WHERE f.channel_id = c.id AND " + OnboardingFacts.PLATFORM_FETCH;
    private static final String HAS_BOOKINGS =
            "SELECT 1 FROM calendar_blocks b WHERE b.channel_id = c.id AND b.cancelled_at IS NULL AND NOT b.ignored";

    @Scheduled(fixedDelayString = "${app.onboarding.notify-interval-ms:120000}", initialDelay = 60_000)
    public void tick() {
        try {
            check();
        } catch (RuntimeException e) {
            log.warn("Сопровождение онбординга: проход не удался: {}", e.getMessage());
        }
    }

    /** @return сколько сообщений создано за проход */
    public int check() {
        return announce(FETCHED, WAS_FETCHED, "notice.fetched") + announce(BOOKINGS, HAS_BOOKINGS, "notice.bookings");
    }

    private int announce(String kind, String condition, String textKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(String.format(CANDIDATES, condition), kind);
        int created = 0;
        for (Map<String, Object> row : rows) {
            Long channelId = ((Number) row.get("id")).longValue();
            Long tenantId = ((Number) row.get("tenant_id")).longValue();
            String text = OnboardingTexts.format(textKey, row.get("name"));
            // Запись — раньше отправки: если два прохода пересекутся, второй ничего не вставит и не отправит
            int inserted = jdbc.update("INSERT INTO onboarding_notices(tenant_id, channel_id, kind, text)"
                    + " VALUES (?, ?, ?, ?) ON CONFLICT (channel_id, kind) DO NOTHING", tenantId, channelId, kind, text);
            if (inserted == 0) continue;
            created++;
            if (telegram.isConfigured(tenantId)) {
                TelegramService.SendResult sent = telegram.send(tenantId, text);
                if (!sent.ok()) log.warn("Сопровождение онбординга: Telegram tenant={}: {}", tenantId, sent.error());
            }
        }
        return created;
    }

    /** Сообщения, которые хозяин ещё не видел; после вызова считаются показанными. */
    public List<String> takeUnshown(Long tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "UPDATE onboarding_notices SET shown_at = NOW() WHERE tenant_id = ? AND shown_at IS NULL"
                        + " RETURNING id, text", tenantId);
        List<String> texts = new ArrayList<>();
        rows.stream().sorted((a, b) -> Long.compare(((Number) a.get("id")).longValue(), ((Number) b.get("id")).longValue()))
                .forEach(r -> texts.add((String) r.get("text")));
        return texts;
    }
}
