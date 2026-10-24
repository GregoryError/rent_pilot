package ru.rentoptima.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Выгрузка данных аккаунта (тенанта) по запросу пользователя — ZIP с JSON:
 * <ul>
 *   <li>{@code account.json} — аккаунт, его пользователи, сведения об операторе для гостей;</li>
 *   <li>{@code consents.json} — принятые редакции документов и согласия на распространение;</li>
 *   <li>{@code properties.json} — объекты и категории;</li>
 *   <li>{@code settings.json} — настройки (без зашифрованных) и уведомления;</li>
 *   <li>{@code channels.json} — площадки без ссылок, секретов и текстов ошибок;</li>
 *   <li>{@code booking_pages.json} — страницы бронирования, промокоды, карточки (без пароля Wi-Fi);</li>
 *   <li>{@code guests.json} — отдельно: брони, записи календаря и отзывы — в них данные гостей.</li>
 * </ul>
 * Колонки с секретами не выгружаются никогда: явный список по таблицам плюс любое имя с
 * {@code secret}, {@code token}, {@code hash}, {@code password}, {@code _enc}.
 */
@Service
public class AccountExport {

    /** Имя колонки, которую не выгружаем ни из одной таблицы. */
    static final Pattern SECRET_COLUMN = Pattern.compile(".*(secret|token|hash|password|_enc).*");

    /** Сверх шаблона: что ещё не выгружать по таблицам. */
    private static final Map<String, Set<String>> DROP = Map.of(
            "channels", Set.of("config_json", "last_error"),
            "users", Set.of("email_verification_expires_at", "password_reset_expires_at"));

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final JdbcTemplate jdbc;

    public AccountExport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Export(byte[] zip, List<String> files) {}

    public Export export(long tenantId) {
        Map<String, Object> files = new LinkedHashMap<>();

        Map<String, Object> account = new LinkedHashMap<>();
        account.put("account", one("tenants", "SELECT id, name, active, created_at FROM tenants WHERE id = ?", tenantId));
        account.put("users", rows("users", "SELECT * FROM users WHERE tenant_id = ? ORDER BY id", tenantId));
        account.put("guest_operator", one("guest_operators", "SELECT * FROM guest_operators WHERE tenant_id = ?", tenantId));
        files.put("account.json", account);

        Map<String, Object> consents = new LinkedHashMap<>();
        consents.put("documents", rows("users", "SELECT id AS user_id, terms_version, privacy_version, consent_version,"
                + " agreed_to_terms_at, agreed_to_consent_at, agreed_to_pd_at, legal_reconsent_required"
                + " FROM users WHERE tenant_id = ? ORDER BY id", tenantId));
        consents.put("public_data_consents", rows("public_data_consents",
                "SELECT * FROM public_data_consents WHERE tenant_id = ? ORDER BY id", tenantId));
        files.put("consents.json", consents);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("properties", rows("properties", "SELECT * FROM properties WHERE tenant_id = ? ORDER BY id", tenantId));
        properties.put("unit_types", rows("unit_types", "SELECT * FROM unit_types WHERE tenant_id = ? ORDER BY id", tenantId));
        properties.put("planned_prices", rows("planned_prices",
                "SELECT * FROM planned_prices WHERE tenant_id = ? ORDER BY id", tenantId));
        files.put("properties.json", properties);

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("settings", rows("system_settings", "SELECT key, value FROM system_settings"
                + " WHERE tenant_id = ? AND NOT encrypted ORDER BY key", tenantId));
        settings.put("notifications", rows("notification_settings",
                "SELECT event_type, channel, enabled FROM notification_settings WHERE tenant_id = ? ORDER BY id", tenantId));
        files.put("settings.json", settings);

        files.put("channels.json", Map.of("channels", rows("channels", "SELECT *, config_json->>'platform' AS platform"
                + " FROM channels WHERE tenant_id = ? ORDER BY id", tenantId)));

        Map<String, Object> pages = new LinkedHashMap<>();
        pages.put("booking_pages", rows("booking_widgets", "SELECT * FROM booking_widgets WHERE tenant_id = ? ORDER BY id", tenantId));
        pages.put("promo_codes", rows("promo_codes", "SELECT * FROM promo_codes WHERE tenant_id = ? ORDER BY id", tenantId));
        pages.put("cards", rows("widget_cards", "SELECT * FROM widget_cards WHERE tenant_id = ? ORDER BY widget_id", tenantId));
        pages.put("short_links", rows("widget_qr_links", "SELECT * FROM widget_qr_links WHERE tenant_id = ? ORDER BY id", tenantId));
        pages.put("photos", rows("widget_photos", "SELECT id, widget_id, position, width, height, created_at"
                + " FROM widget_photos WHERE tenant_id = ? ORDER BY widget_id, position", tenantId));
        files.put("booking_pages.json", pages);

        Map<String, Object> guests = new LinkedHashMap<>();
        guests.put("bookings", rows("bookings", "SELECT * FROM bookings WHERE tenant_id = ? ORDER BY id", tenantId));
        guests.put("calendar_records", rows("calendar_blocks", "SELECT * FROM calendar_blocks WHERE tenant_id = ?"
                + " AND channel_id IS NULL ORDER BY id", tenantId));
        guests.put("feedback", rows("feedback_responses", "SELECT r.* FROM feedback_responses r JOIN properties p"
                + " ON p.id = r.property_id WHERE p.tenant_id = ? ORDER BY r.id", tenantId));
        guests.put("feedback_answers", rows("feedback_answers", "SELECT a.* FROM feedback_answers a"
                + " JOIN feedback_responses r ON r.id = a.response_id JOIN properties p ON p.id = r.property_id"
                + " WHERE p.tenant_id = ? ORDER BY a.id", tenantId));
        files.put("guests.json", guests);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> names = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, "README.txt", readme().getBytes(StandardCharsets.UTF_8));
            names.add("README.txt");
            for (Map.Entry<String, Object> f : files.entrySet()) {
                put(zip, f.getKey(), JSON.writeValueAsBytes(f.getValue()));
                names.add(f.getKey());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось собрать выгрузку", e);
        }
        return new Export(out.toByteArray(), names);
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private Map<String, Object> one(String table, String sql, Object... args) {
        List<Map<String, Object>> r = rows(table, sql, args);
        return r.isEmpty() ? null : r.get(0);
    }

    private List<Map<String, Object>> rows(String table, String sql, Object... args) {
        Set<String> drop = DROP.getOrDefault(table, Set.of());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                String col = e.getKey().toLowerCase(Locale.ROOT);
                if (drop.contains(col) || SECRET_COLUMN.matcher(col).matches()) continue;
                clean.put(col, value(e.getValue()));
            }
            out.add(clean);
        }
        return out;
    }

    private static Object value(Object v) {
        try {
            if (v == null) return null;
            if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime().toString();
            if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
            if (v instanceof java.sql.Time t) return t.toLocalTime().toString();
            if (v instanceof OffsetDateTime t) return t.toString();
            // jsonb и прочие типы PostgreSQL (драйвер — только в рантайме): значение — toString()
            if (v.getClass().getName().equals("org.postgresql.util.PGobject")) {
                String text = v.toString();
                if (text == null) return null;
                try {
                    return JSON.readTree(text);
                } catch (IOException notJson) {
                    return text;
                }
            }
            if (v instanceof Array a) {
                Object arr = a.getArray();
                List<Object> list = new ArrayList<>();
                if (arr instanceof Object[] items) for (Object i : items) list.add(value(i));
                return list;
            }
            return v;
        } catch (SQLException e) {
            return String.valueOf(v);
        }
    }

    private static String readme() {
        return """
                Выгрузка данных аккаунта OptiRent.

                account.json        — аккаунт, пользователи, сведения об операторе для гостей
                consents.json       — принятые редакции документов, согласия на распространение
                properties.json     — объекты, категории, плановые цены
                settings.json       — настройки и уведомления
                channels.json       — площадки (без ссылок и ключей доступа)
                booking_pages.json  — страницы бронирования, промокоды, карточки, короткие ссылки
                guests.json         — брони, записи календаря и отзывы: в них данные гостей

                Пароли, их хеши, ключи ссылок, токены и пароль Wi-Fi в выгрузку не входят.
                Даты — по московскому времени, если не указан часовой пояс.
                """;
    }
}
