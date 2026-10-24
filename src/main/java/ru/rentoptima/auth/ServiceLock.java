package ru.rentoptima.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ru.rentoptima.legal.LegalMarkdown;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Выключатель «Сервис открыт» ({@code service_settings}, V50; страница /admin/service).
 * <ul>
 *   <li>закрыта регистрация — /register показывает объявление, POST отклоняется;</li>
 *   <li>закрыт вход — входит только администратор, сессии хозяев завершаются, /forgot, /reset и
 *       /verify отвечают объявлением. Без входа нет и регистрации: новичок не смог бы войти.</li>
 * </ul>
 * Не затрагивает ничего публичного: страницы бронирования, виджет, фиды, анкеты, расписание
 * горничной, короткие ссылки, правовые страницы, сайт, медиа.
 * <p>
 * {@code APP_SERVICE_LOCK_OVERRIDE=open} — аварийный выход: всё открыто, настройка не читается.
 */
@Component
public class ServiceLock {

    public static final String REGISTRATION = "registration_open";
    public static final String LOGIN = "login_open";
    public static final String TITLE = "notice_title";
    public static final String TEXT = "notice_text";

    /** Объявление: заголовок и безопасный HTML текста (абзацы, ссылки). */
    public record Notice(String title, String html) {}

    public record State(boolean registrationOpen, boolean loginOpen, String title, String text) {}

    private static final long TTL_MS = 5_000;

    private final JdbcTemplate jdbc;
    private final boolean override;
    private volatile State cached;
    private volatile long cachedAt;

    public ServiceLock(JdbcTemplate jdbc, @Value("${app.service.lock-override:}") String override) {
        this.jdbc = jdbc;
        this.override = "open".equalsIgnoreCase(override == null ? "" : override.strip());
    }

    /** Включён аварийный выход APP_SERVICE_LOCK_OVERRIDE=open. */
    public boolean overridden() {
        return override;
    }

    /** Сохранённая настройка — как её видит админка (без учёта аварийного выхода). */
    public State state() {
        State s = cached;
        if (s == null || System.currentTimeMillis() - cachedAt > TTL_MS) {
            Map<String, String> v = new HashMap<>();
            jdbc.query("SELECT key, value FROM service_settings", rs -> {
                v.put(rs.getString("key"), rs.getString("value"));
            });
            s = new State(!"false".equals(v.get(REGISTRATION)), !"false".equals(v.get(LOGIN)),
                    v.getOrDefault(TITLE, "Сервис на стадии запуска"),
                    v.getOrDefault(TEXT, "Регистрация откроется в ближайшее время."));
            cached = s;
            cachedAt = System.currentTimeMillis();
        }
        return s;
    }

    public boolean loginOpen() {
        return override || state().loginOpen();
    }

    /** Регистрация открыта и вход открыт: без входа зарегистрированный не попал бы в кабинет. */
    public boolean registrationOpen() {
        return override || (state().registrationOpen() && state().loginOpen());
    }

    public Notice notice() {
        State s = state();
        return new Notice(s.title(), LegalMarkdown.render(s.text(), false).html());
    }

    /** Сохранить; возвращает ключи, которые изменились. */
    public List<String> save(boolean registrationOpen, boolean loginOpen, String title, String text, Long userId) {
        State before = state();
        Map<String, String> next = new java.util.LinkedHashMap<>();
        next.put(REGISTRATION, String.valueOf(registrationOpen));
        next.put(LOGIN, String.valueOf(loginOpen));
        next.put(TITLE, title);
        next.put(TEXT, text);
        Map<String, String> prev = Map.of(REGISTRATION, String.valueOf(before.registrationOpen()),
                LOGIN, String.valueOf(before.loginOpen()), TITLE, before.title(), TEXT, before.text());
        List<String> changed = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : next.entrySet()) {
            if (e.getValue().equals(prev.get(e.getKey()))) continue;
            changed.add(e.getKey());
            jdbc.update("INSERT INTO service_settings(key, value, updated_by_user_id) VALUES (?, ?, ?)"
                    + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(),"
                    + " updated_by_user_id = EXCLUDED.updated_by_user_id", e.getKey(), e.getValue(), userId);
        }
        cached = null;
        return changed;
    }
}
