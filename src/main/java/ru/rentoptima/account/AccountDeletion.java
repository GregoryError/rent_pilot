package ru.rentoptima.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.rentoptima.auth.PasswordService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Удаление аккаунта (тенанта) со всеми пользователями по запросу — из /admin/users/<id>/delete.
 * <p>
 * Порядок:
 * <ol>
 *   <li>завершить сессии пользователей аккаунта;</li>
 *   <li>запомнить хеши публичных адресов ({@link GoneLinks}) — они будут отвечать 410;</li>
 *   <li>удалить строки — от зависимых к главным ({@link #STEPS}), затем пользователей и тенант;</li>
 *   <li>проверить по {@code information_schema}, что ни в одной таблице с {@code tenant_id} не
 *       осталось строк аккаунта. Осталась — исключение, транзакция откатывается целиком: новая
 *       таблица, не попавшая в список, не оставит хвостов, а остановит удаление;</li>
 *   <li>после фиксации — удалить файлы фото страниц бронирования из тома загрузок.</li>
 * </ol>
 * Аккаунт с администратором не удаляется. Бэкапы базы и тома хранятся 30 дней и не правятся:
 * данные из них уходят вместе с их сроком.
 */
@Service
public class AccountDeletion {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletion.class);

    /** Что посчитать до удаления — для журнала (числа, без имён). */
    public record Summary(int users, int properties, int bookings, int pages) {}

    /** Объекты аккаунта — для таблиц без tenant_id. */
    private static final String PROPS = "(SELECT id FROM properties WHERE tenant_id = ?)";

    /** DELETE по порядку: каждый — с одним параметром tenant_id (столько раз, сколько в нём «?»). */
    static final List<String> STEPS = List.of(
            // отзывы: ответы → анкеты → вопросы
            "DELETE FROM feedback_answers WHERE response_id IN (SELECT id FROM feedback_responses WHERE property_id IN " + PROPS + ")"
                    + " OR question_id IN (SELECT id FROM feedback_questions WHERE tenant_id = ?)",
            "DELETE FROM feedback_responses WHERE property_id IN " + PROPS,
            "DELETE FROM feedback_questions WHERE tenant_id = ?",
            // цены конкурентов и история цен
            "DELETE FROM competitor_prices WHERE listing_id IN (SELECT id FROM competitor_listings WHERE tenant_id = ?)",
            "DELETE FROM competitor_daily_prices WHERE tenant_id = ?",
            "DELETE FROM competitor_listings WHERE tenant_id = ?",
            "DELETE FROM competitor_searches WHERE tenant_id = ?",
            "DELETE FROM price_history WHERE property_id IN " + PROPS,
            // чат и AI
            "DELETE FROM manual_overrides WHERE tenant_id = ?",
            "DELETE FROM chat_messages WHERE tenant_id = ?",
            "DELETE FROM ai_comments WHERE tenant_id = ?",
            "DELETE FROM ai_usage_log WHERE tenant_id = ?",
            "DELETE FROM city_events WHERE tenant_id = ?",
            "DELETE FROM external_sources WHERE tenant_id = ?",
            // расходы
            "DELETE FROM expenses WHERE tenant_id = ?",
            "DELETE FROM expense_categories WHERE tenant_id = ?",
            // страницы бронирования: карточки, ссылки, воронка, фото, согласия, брони, промокоды
            "DELETE FROM widget_cards WHERE tenant_id = ?",
            "DELETE FROM widget_qr_links WHERE tenant_id = ?",
            "DELETE FROM widget_funnel_daily WHERE tenant_id = ?",
            "DELETE FROM widget_photos WHERE tenant_id = ?",
            "DELETE FROM public_data_consents WHERE tenant_id = ?",
            "DELETE FROM bookings WHERE tenant_id = ?",
            "DELETE FROM promo_codes WHERE tenant_id = ?",
            "DELETE FROM booking_widgets WHERE tenant_id = ?",
            // календарь и площадки
            "DELETE FROM manual_block_echoes WHERE tenant_id = ?",
            "DELETE FROM calendar_blocks WHERE tenant_id = ?",
            "DELETE FROM alert_events WHERE tenant_id = ?",
            "DELETE FROM channel_feed_fetches WHERE tenant_id = ?",
            "DELETE FROM onboarding_notices WHERE tenant_id = ?",
            "DELETE FROM planned_prices WHERE tenant_id = ?",
            "DELETE FROM pricing_decisions WHERE tenant_id = ?",
            "DELETE FROM pricing_insights WHERE tenant_id = ?",
            "DELETE FROM pricing_rules WHERE tenant_id = ?",
            "DELETE FROM rc_action_log WHERE tenant_id = ?",
            "DELETE FROM channels WHERE tenant_id = ?",
            "DELETE FROM unit_types WHERE tenant_id = ?",
            "DELETE FROM properties WHERE tenant_id = ?",
            // аккаунт
            "DELETE FROM guest_operators WHERE tenant_id = ?",
            "DELETE FROM system_settings WHERE tenant_id = ?",
            "DELETE FROM notification_settings WHERE tenant_id = ?",
            "DELETE FROM onboarding_state WHERE tenant_id = ?",
            // ссылки на пользователя без каскада
            "UPDATE legal_pages SET updated_by_user_id = NULL WHERE updated_by_user_id IN (SELECT id FROM users WHERE tenant_id = ?)",
            "DELETE FROM auth_tokens WHERE user_id IN (SELECT id FROM users WHERE tenant_id = ?)",
            "DELETE FROM users WHERE tenant_id = ?");

    private final JdbcTemplate jdbc;
    private final GoneLinks gone;
    private final PasswordService passwords;
    private final Path widgetDir;

    public AccountDeletion(JdbcTemplate jdbc, GoneLinks gone, PasswordService passwords,
                           @Value("${app.uploads.dir:uploads}") String uploadsDir) {
        this.jdbc = jdbc;
        this.gone = gone;
        this.passwords = passwords;
        this.widgetDir = Path.of(uploadsDir).toAbsolutePath().normalize().resolve("widget");
    }

    public Summary summary(long tenantId) {
        return new Summary(count("SELECT count(*) FROM users WHERE tenant_id = ?", tenantId),
                count("SELECT count(*) FROM properties WHERE tenant_id = ?", tenantId),
                count("SELECT count(*) FROM bookings WHERE tenant_id = ?", tenantId),
                count("SELECT count(*) FROM booking_widgets WHERE tenant_id = ?", tenantId));
    }

    /** Удаляет аккаунт целиком. Вызывающий уже проверил, что администратора в нём нет. */
    @Transactional
    public Summary delete(long tenantId) {
        Integer admins = jdbc.queryForObject("SELECT count(*) FROM users WHERE tenant_id = ? AND role = 'ADMIN'",
                Integer.class, tenantId);
        if (admins != null && admins > 0) throw new IllegalStateException("В аккаунте есть администратор");
        Summary summary = summary(tenantId);

        for (Long userId : jdbc.queryForList("SELECT id FROM users WHERE tenant_id = ?", Long.class, tenantId)) {
            passwords.expireSessions(userId, null);
        }

        // Публичные адреса — до удаления строк, в той же транзакции
        gone.remember(GoneLinks.Kind.PAGE, list("SELECT slug FROM booking_widgets WHERE tenant_id = ? AND slug IS NOT NULL", tenantId));
        gone.remember(GoneLinks.Kind.SECRET, list("SELECT secret FROM booking_widgets WHERE tenant_id = ?", tenantId));
        gone.remember(GoneLinks.Kind.QR, list("SELECT code FROM widget_qr_links WHERE tenant_id = ?", tenantId));
        gone.remember(GoneLinks.Kind.FEEDBACK, list("SELECT feedback_code FROM properties WHERE tenant_id = ?", tenantId));
        gone.remember(GoneLinks.Kind.HOUSEKEEPER, list("SELECT housekeeper_code FROM properties WHERE tenant_id = ?", tenantId));
        gone.remember(GoneLinks.Kind.ICAL, list("SELECT export_secret FROM channels WHERE tenant_id = ?"
                + " AND export_secret IS NOT NULL", tenantId));

        // Файлы фото: имена — до удаления строк, сами файлы — после фиксации
        List<Path> files = new ArrayList<>();
        List<java.util.Map<String, Object>> photos = jdbc.queryForList(
                "SELECT file_key, widths FROM widget_photos WHERE tenant_id = ?", tenantId);
        for (java.util.Map<String, Object> p : photos) {
            String key = String.valueOf(p.get("file_key")).trim();
            for (String w : String.valueOf(p.get("widths")).split(",")) {
                files.add(widgetDir.resolve(key + "-" + w.trim() + ".jpg"));
                files.add(widgetDir.resolve(key + "-" + w.trim() + ".webp"));
            }
        }

        for (String sql : STEPS) {
            int params = (int) sql.chars().filter(c -> c == '?').count();
            Object[] args = new Object[params];
            java.util.Arrays.fill(args, tenantId);
            jdbc.update(sql, args);
        }
        jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);

        List<String> left = leftovers(tenantId);
        if (!left.isEmpty()) {
            throw new IllegalStateException("После удаления остались строки аккаунта в таблицах " + left
                    + " — добавьте их в AccountDeletion.STEPS");
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteFiles(files);
                }
            });
        } else {
            deleteFiles(files);
        }
        log.info("Аккаунт {} удалён по запросу: пользователей {}, объектов {}, броней {}, страниц {}, фото {}",
                tenantId, summary.users(), summary.properties(), summary.bookings(), summary.pages(), photos.size());
        return summary;
    }

    /** Таблицы с tenant_id, где ещё есть строки аккаунта. */
    public List<String> leftovers(long tenantId) {
        List<String> left = new ArrayList<>();
        for (String table : jdbc.queryForList("SELECT table_name FROM information_schema.columns"
                + " WHERE table_schema = current_schema() AND column_name = 'tenant_id' ORDER BY 1", String.class)) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM \"" + table.replace("\"", "") + "\" WHERE tenant_id = ?",
                    Integer.class, tenantId);
            if (n != null && n > 0) left.add(table);
        }
        Integer t = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, tenantId);
        if (t != null && t > 0) left.add("tenants");
        return left;
    }

    private void deleteFiles(List<Path> files) {
        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                log.warn("Не удалось удалить файл {}: {}", f.getFileName(), e.getMessage());
            }
        }
    }

    private List<String> list(String sql, long tenantId) {
        return jdbc.queryForList(sql, String.class, tenantId);
    }

    private int count(String sql, long tenantId) {
        Integer n = jdbc.queryForObject(sql, Integer.class, tenantId);
        return n == null ? 0 : n;
    }
}
