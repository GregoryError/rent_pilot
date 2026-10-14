package ru.rentoptima.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.rentoptima.service.EmailService;
import ru.rentoptima.service.TelegramService;

import java.util.List;
import java.util.Locale;

/**
 * Режим подтверждения почты при регистрации — настройка всего сервиса, хранится в
 * system_settings у тенанта администратора:
 * <ul>
 *   <li>SOFT — в кабинет сразу, подтверждение в течение 7 дней;</li>
 *   <li>STRICT — в кабинет только после подтверждения (экран «Проверьте почту»);</li>
 *   <li>AUTO — SOFT, пока «настоящих» пользователей меньше порога; на пороге настройка сама
 *       переключается на STRICT, и администратор получает сообщение в Telegram.</li>
 * </ul>
 * «Настоящий» — активный не-администратор, который подтвердил почту по ссылке или создал хотя
 * бы один объект. Без SMTP режим всегда SOFT: подтвердить адрес нечем.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationMode {

    public enum Mode { SOFT, STRICT, AUTO }

    public static final String KEY_MODE = "registration_verification";
    public static final String KEY_THRESHOLD = "registration_auto_threshold";
    public static final int DEFAULT_THRESHOLD = 100;

    /** Сводка для страницы администратора. */
    public record Counts(long total, long real, long unverified, long disabled) {}

    private final JdbcTemplate jdbc;
    private final EmailService email;
    private final TelegramService telegram;

    /** Тенант администратора — у него лежит настройка; null, если администратора нет. */
    Long adminTenant() {
        List<Long> ids = jdbc.queryForList("SELECT tenant_id FROM users WHERE role = 'ADMIN' ORDER BY id LIMIT 1", Long.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String read(String key) {
        Long tenant = adminTenant();
        if (tenant == null) return null;
        List<String> v = jdbc.queryForList("SELECT value FROM system_settings WHERE tenant_id = ? AND key = ?",
                String.class, tenant, key);
        return v.isEmpty() ? null : v.get(0);
    }

    private void write(String key, String value, String description) {
        Long tenant = adminTenant();
        if (tenant == null) return;
        jdbc.update("INSERT INTO system_settings (tenant_id, key, value, description) VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (tenant_id, key) DO UPDATE SET value = EXCLUDED.value", tenant, key, value, description);
    }

    /** Режим, выбранный администратором (по умолчанию AUTO). */
    public Mode configured() {
        String v = read(KEY_MODE);
        try {
            return v == null ? Mode.AUTO : Mode.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Mode.AUTO;
        }
    }

    public int threshold() {
        try {
            String v = read(KEY_THRESHOLD);
            int n = v == null ? DEFAULT_THRESHOLD : Integer.parseInt(v.trim());
            return n > 0 ? n : DEFAULT_THRESHOLD;
        } catch (NumberFormatException e) {
            return DEFAULT_THRESHOLD;
        }
    }

    public void configure(Mode mode, int threshold) {
        write(KEY_MODE, mode.name(), "Подтверждение почты при регистрации: SOFT / STRICT / AUTO");
        write(KEY_THRESHOLD, String.valueOf(Math.max(1, threshold)),
                "AUTO: с какого числа настоящих пользователей включать STRICT");
        log.info("Режим подтверждения почты: {} (порог {})", mode, threshold);
    }

    /** Как регистрировать нового пользователя прямо сейчас: SOFT или STRICT. */
    public Mode forNewUser() {
        if (!email.smtpConfigured()) return Mode.SOFT;
        Mode mode = checkThreshold();
        return mode == Mode.STRICT ? Mode.STRICT : Mode.SOFT;
    }

    /**
     * AUTO и порог достигнут — переключает настройку на STRICT и пишет администратору.
     * Вызывается при регистрации и раз в 15 минут (порог могут перейти и подтверждения почты).
     *
     * @return режим после проверки
     */
    @Scheduled(initialDelay = 120_000, fixedDelay = 900_000)
    public synchronized Mode checkThreshold() {
        Mode mode = configured();
        if (mode != Mode.AUTO || !email.smtpConfigured()) return mode;
        long real = counts().real();
        int threshold = threshold();
        if (real < threshold) return Mode.AUTO;
        configure(Mode.STRICT, threshold);
        Long tenant = adminTenant();
        if (tenant != null && telegram.isConfigured(tenant)) {
            telegram.send(tenant, "OptiRent: настоящих пользователей стало " + real + " (порог " + threshold + ").\n"
                    + "Регистрация переключена в STRICT: новые пользователи попадают в кабинет только после "
                    + "подтверждения почты. Изменить — Администрирование → Регистрация.");
        }
        log.warn("Порог настоящих пользователей ({}) достигнут: регистрация переключена в STRICT", threshold);
        return Mode.STRICT;
    }

    public Counts counts() {
        return jdbc.queryForObject("SELECT"
                        + " count(*) AS total,"
                        + " count(*) FILTER (WHERE u.active AND (u.email_verified_at IS NOT NULL"
                        + "   OR EXISTS (SELECT 1 FROM properties p WHERE p.tenant_id = u.tenant_id))) AS real,"
                        + " count(*) FILTER (WHERE u.active AND NOT u.email_verified AND u.email IS NOT NULL) AS unverified,"
                        + " count(*) FILTER (WHERE NOT u.active) AS disabled"
                        + " FROM users u WHERE u.role <> 'ADMIN'",
                (rs, i) -> new Counts(rs.getLong("total"), rs.getLong("real"), rs.getLong("unverified"), rs.getLong("disabled")));
    }
}
