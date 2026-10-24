package ru.rentoptima.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.account.AccountDeletion;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.security.TenantUserDetails;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Выгрузка и удаление аккаунта: в выгрузке есть данные аккаунта и гостей (отдельным файлом) и нет
 * секретов; после удаления в базе не остаётся строк аккаунта, публичные адреса отвечают 410,
 * файлы фото удалены, в журнале — запись без персональных данных. Нужен docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.uploads.dir=target/test-uploads"})
@AutoConfigureMockMvc
@DisplayName("Админка: выгрузка и удаление аккаунта")
class AccountDeletionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String PASSWORD = "Длинный-пароль-123";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountDeletion deletion;

    private TenantUserDetails admin;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Админ', ?) RETURNING id",
                Long.class, "ad-admin-" + tag);
        Long id = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role)"
                + " VALUES (?, ?, 'x', 'ADMIN') RETURNING id", Long.class, tenantId, "ad-admin-" + tag);
        Tenant t = new Tenant();
        t.setId(tenantId);
        User u = new User();
        u.setId(id);
        u.setTenant(t);
        u.setUsername("ad-admin-" + tag);
        u.setPasswordHash("x");
        u.setRole(User.Role.ADMIN);
        admin = new TenantUserDetails(u);
    }

    /** Аккаунт со всем подряд. В secrets — значения, которых не должно быть в выгрузке. */
    record Seeded(long tenantId, long userId, long secondUserId, String email, String slug, String widgetSecret,
                  String qrBooking, String qrFeedback, String feedbackCode, String housekeeperCode,
                  String exportSecret, Path photo, Map<String, String> secrets) {}

    private Seeded seed() throws Exception {
        Map<String, String> secrets = new HashMap<>();
        long t = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES (?, ?) RETURNING id", Long.class,
                "Удаляемый " + tag, "del-" + tag);
        String email = "del-" + tag + "@example.com";
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        secrets.put("hash", hash);
        long u = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, display_name, role, email,"
                        + " email_verified, consent_version, terms_version, privacy_version, agreed_to_consent_at)"
                        + " VALUES (?, ?, ?, 'Хозяйка', 'OWNER', ?, TRUE, 1, 1, 1, now()) RETURNING id", Long.class,
                t, email, hash, email);
        long u2 = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role, email)"
                + " VALUES (?, ?, 'x', 'OWNER', ?) RETURNING id", Long.class, t, "second-" + tag, "second-" + tag + "@example.com");
        jdbc.update("INSERT INTO auth_tokens(user_id, kind, token_hash, expires_at) VALUES (?, 'RESET', ?, now() + interval '1 hour')",
                u, ("abc" + tag).repeat(8).substring(0, 64));
        secrets.put("token", ("abc" + tag).repeat(8).substring(0, 64));
        jdbc.update("INSERT INTO onboarding_state(user_id, tenant_id) VALUES (?, ?)", u, t);
        jdbc.update("INSERT INTO system_settings(tenant_id, key, value) VALUES (?, 'city', 'Сочи')", t);
        jdbc.update("INSERT INTO system_settings(tenant_id, key, value, encrypted) VALUES (?, 'telegram_bot_token', ?, TRUE)",
                t, "ENCVALUE" + tag);
        secrets.put("encrypted", "ENCVALUE" + tag);
        jdbc.update("INSERT INTO notification_settings(tenant_id, event_type) VALUES (?, 'NEW_BOOKING')", t);
        jdbc.update("INSERT INTO guest_operators(tenant_id, name, contact) VALUES (?, 'ИП Хозяйка', 'host@example.com')", t);

        String feedbackCode = "fb" + tag;
        String housekeeperCode = "hk" + tag;
        long p = jdbc.queryForObject("INSERT INTO properties(tenant_id, name, feedback_code, housekeeper_code,"
                        + " housekeeper_pin_hash) VALUES (?, 'Дом у моря', ?, ?, ?) RETURNING id", Long.class,
                t, feedbackCode, housekeeperCode, "PINHASH" + tag);
        secrets.put("pin", "PINHASH" + tag);
        long ut = jdbc.queryForObject("INSERT INTO unit_types(tenant_id, property_id, name, unit_count, base_price)"
                + " VALUES (?, ?, 'Дом', 1, 5000) RETURNING id", Long.class, t, p);
        jdbc.update("INSERT INTO planned_prices(tenant_id, unit_type_id, date, price) VALUES (?, ?, ?, 6000)",
                t, ut, LocalDate.now().plusDays(5));

        String exportSecret = "exp" + tag + "secretsecret";
        long ical = jdbc.queryForObject("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled,"
                        + " export_secret, config_json, last_error) VALUES (?, ?, 'ICAL', 'Циан', TRUE, ?, ?::jsonb, ?) RETURNING id",
                Long.class, t, ut, exportSecret,
                "{\"import_url\": \"https://cian.ru/ical/IMPORT" + tag + ".ics\", \"platform\": \"cian\"}", "ERR" + tag);
        secrets.put("export", exportSecret);
        secrets.put("import", "IMPORT" + tag);
        secrets.put("error", "ERR" + tag);
        jdbc.update("INSERT INTO channel_feed_fetches(tenant_id, channel_id) VALUES (?, ?)", t, ical);
        LocalDate from = LocalDate.now().plusDays(3);
        long manual = jdbc.queryForObject("INSERT INTO calendar_blocks(tenant_id, unit_type_id, block_type, from_date, to_date,"
                + " created_by_user_id) VALUES (?, ?, 'MANUAL_BOOKING', ?, ?, ?) RETURNING id", Long.class, t, ut, from, from.plusDays(2), u);
        jdbc.update("INSERT INTO calendar_blocks(tenant_id, unit_type_id, channel_id, block_type, from_date, to_date, external_uid)"
                + " VALUES (?, ?, ?, 'CHANNEL_SYNC', ?, ?, 'uid-1')", t, ut, ical, from.plusDays(10), from.plusDays(12));
        jdbc.update("INSERT INTO manual_block_echoes(tenant_id, manual_block_id, channel_id, external_uid) VALUES (?, ?, ?, 'echo-1')",
                t, manual, ical);

        long wch = jdbc.queryForObject("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled)"
                + " VALUES (?, ?, 'WIDGET', 'Страница', FALSE) RETURNING id", Long.class, t, ut);
        String slug = "del-" + tag;
        String widgetSecret = "wsec" + tag + "abcdefabcdef";
        long w = jdbc.queryForObject("INSERT INTO booking_widgets(tenant_id, channel_id, unit_type_id, secret, slug, title)"
                + " VALUES (?, ?, ?, ?, ?, 'Дом у моря напрямую') RETURNING id", Long.class, t, wch, ut, widgetSecret, slug);
        secrets.put("widget", widgetSecret);
        long promo = jdbc.queryForObject("INSERT INTO promo_codes(tenant_id, widget_id, code, discount_type, discount_value)"
                + " VALUES (?, ?, 'LETO', 'PERCENT', 10) RETURNING id", Long.class, t, w);
        String qrBooking = ("q" + tag).substring(0, 7);
        String qrFeedback = ("f" + tag).substring(0, 7);
        jdbc.update("INSERT INTO widget_qr_links(tenant_id, widget_id, code, kind, promo_code_id) VALUES (?, ?, ?, 'BOOKING', ?)",
                t, w, qrBooking, promo);
        jdbc.update("INSERT INTO widget_qr_links(tenant_id, widget_id, code, kind) VALUES (?, ?, ?, 'FEEDBACK')", t, w, qrFeedback);
        String key = (tag + "0123456789abcdef0123456789abcdef").substring(0, 32);
        long photo = jdbc.queryForObject("INSERT INTO widget_photos(tenant_id, widget_id, file_key, width, height, widths, lqip)"
                + " VALUES (?, ?, ?, 480, 300, '480', 'data:image/jpeg;base64,AA==') RETURNING id", Long.class, t, w, key);
        Path dir = Path.of("target/test-uploads/widget").toAbsolutePath();
        Files.createDirectories(dir);
        Path file = dir.resolve(key + "-480.jpg");
        Files.write(file, new byte[]{1, 2, 3});
        jdbc.update("INSERT INTO widget_cards(widget_id, tenant_id, promo_code_id, photo_id, wifi_ssid, wifi_password_enc)"
                + " VALUES (?, ?, ?, ?, 'Home', ?)", w, t, promo, photo, "WIFIENC" + tag);
        secrets.put("wifi", "WIFIENC" + tag);
        jdbc.update("INSERT INTO public_data_consents(tenant_id, widget_id, user_id, fields, document_version)"
                + " VALUES (?, ?, ?, 'name', 1)", t, w, u);

        long b = jdbc.queryForObject("INSERT INTO bookings(tenant_id, property_id, channel_id, unit_type_id, status, guest_name,"
                        + " guest_phone, guest_email, check_in, check_out, nights, promo_code_id)"
                        + " VALUES (?, ?, ?, ?, 'BOOKED', 'Анна К.', '+79990001122', 'anna@example.com', ?, ?, 2, ?) RETURNING id",
                Long.class, t, p, wch, ut, from.plusDays(20), from.plusDays(22), promo);
        long q = jdbc.queryForObject("INSERT INTO feedback_questions(tenant_id, property_id, question_text) VALUES (?, ?, 'Чисто?')"
                + " RETURNING id", Long.class, t, p);
        long r = jdbc.queryForObject("INSERT INTO feedback_responses(property_id, booking_id, session_id, guest_name, completed)"
                + " VALUES (?, ?, ?, 'Анна', TRUE) RETURNING id", Long.class, p, b, "sess-" + tag);
        jdbc.update("INSERT INTO feedback_answers(response_id, question_id, text_value) VALUES (?, ?, 'Отлично')", r, q);
        long cat = jdbc.queryForObject("INSERT INTO expense_categories(tenant_id, name) VALUES (?, 'Уборка') RETURNING id",
                Long.class, t);
        jdbc.update("INSERT INTO expenses(tenant_id, category_id, property_id, booking_id, amount, date)"
                + " VALUES (?, ?, ?, ?, 1500, current_date)", t, cat, p, b);
        return new Seeded(t, u, u2, email, slug, widgetSecret, qrBooking, qrFeedback, feedbackCode, housekeeperCode,
                exportSecret, file, secrets);
    }

    private Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                files.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    @DisplayName("выгрузка: аккаунт, объекты, настройки, площадки, страницы, согласия; гости — отдельно; секретов нет")
    void export() throws Exception {
        Seeded s = seed();
        MvcResult r = mvc.perform(post("/admin/users/" + s.userId() + "/export").with(user(admin)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentType()).isEqualTo("application/zip");
        Map<String, String> files = unzip(r.getResponse().getContentAsByteArray());
        assertThat(files).containsOnlyKeys("README.txt", "account.json", "consents.json", "properties.json",
                "settings.json", "channels.json", "booking_pages.json", "guests.json");
        assertThat(files.get("account.json")).contains(s.email(), "second-" + tag, "ИП Хозяйка");
        assertThat(files.get("properties.json")).contains("Дом у моря", "6000");
        assertThat(files.get("settings.json")).contains("Сочи", "NEW_BOOKING").doesNotContain("telegram_bot_token");
        assertThat(files.get("channels.json")).contains("Циан", "\"platform\" : \"cian\"");
        assertThat(files.get("booking_pages.json")).contains("Дом у моря напрямую", "LETO", s.qrBooking(), "Home");
        assertThat(files.get("consents.json")).contains("\"consent_version\" : 1", "public_data_consents");
        // данные гостей — только в guests.json
        assertThat(files.get("guests.json")).contains("Анна К.", "+79990001122", "anna@example.com", "Отлично");
        for (Map.Entry<String, String> f : files.entrySet()) {
            if (!f.getKey().equals("guests.json")) {
                assertThat(f.getValue()).as(f.getKey()).doesNotContain("+79990001122", "anna@example.com");
            }
            for (Map.Entry<String, String> secret : s.secrets().entrySet()) {
                assertThat(f.getValue()).as(f.getKey() + " / " + secret.getKey()).doesNotContain(secret.getValue());
            }
            assertThat(f.getValue()).as(f.getKey()).doesNotContain("password_hash", "export_secret", "wifi_password");
        }
        Map<String, Object> a = jdbc.queryForMap("SELECT result, details FROM admin_audit_log WHERE action = 'USER_EXPORT'"
                + " AND target_id = ? ORDER BY id DESC LIMIT 1", s.userId());
        assertThat(a.get("result")).isEqualTo("OK");
        assertThat((String) a.get("details")).doesNotContain("@");
    }

    @Test
    @DisplayName("удаление: адрес не совпал — ничего не удалено; совпал — строк аккаунта нет, адреса 410, файлы удалены")
    void delete() throws Exception {
        Seeded s = seed();
        MvcResult login = mvc.perform(formLogin("/login").user(s.email()).password(PASSWORD)).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/b/" + s.slug())).andExpect(status().isOk());
        mvc.perform(get("/ical/" + s.exportSecret() + ".ics")).andExpect(status().isOk());

        mvc.perform(get("/admin/users/" + s.userId() + "/delete").with(user(admin))).andExpect(status().isOk());
        mvc.perform(post("/admin/users/" + s.userId() + "/delete").param("confirmEmail", "wrong@example.com")
                .with(user(admin)).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE tenant_id = ?", Integer.class, s.tenantId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT result FROM admin_audit_log WHERE action = 'USER_DELETE' AND target_id = ?"
                + " ORDER BY id DESC LIMIT 1", String.class, s.userId())).isEqualTo("REFUSED");

        mvc.perform(post("/admin/users/" + s.userId() + "/delete").param("confirmEmail", " " + s.email().toUpperCase() + " ")
                .with(user(admin)).with(csrf())).andExpect(status().is3xxRedirection());

        // ни в одной таблице с tenant_id нет строк аккаунта, и в связанных без tenant_id — тоже
        assertThat(deletion.leftovers(s.tenantId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id IN (?, ?)", Integer.class,
                s.userId(), s.secondUserId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_tokens WHERE user_id IN (?, ?)", Integer.class,
                s.userId(), s.secondUserId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback_responses WHERE session_id = ?", Integer.class,
                "sess-" + tag)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback_answers WHERE text_value = 'Отлично'"
                + " AND response_id NOT IN (SELECT id FROM feedback_responses)", Integer.class)).isZero();
        assertThat(Files.exists(s.photo())).isFalse();

        // публичные адреса — 410
        mvc.perform(get("/b/" + s.slug())).andExpect(status().isGone());
        mvc.perform(get("/b/" + s.slug() + "/embed")).andExpect(status().isGone());
        mvc.perform(get("/book/" + s.widgetSecret())).andExpect(status().isGone());
        mvc.perform(get("/widget/" + s.widgetSecret())).andExpect(status().isGone());
        mvc.perform(get("/q/" + s.qrBooking())).andExpect(status().isGone());
        mvc.perform(get("/f/" + s.qrFeedback())).andExpect(status().isGone());
        mvc.perform(get("/feedback/" + s.feedbackCode())).andExpect(status().isGone());
        mvc.perform(get("/housekeeper/" + s.housekeeperCode())).andExpect(status().isGone());
        mvc.perform(get("/ical/" + s.exportSecret() + ".ics")).andExpect(status().isGone());
        // незнакомые адреса — по-прежнему 404
        mvc.perform(get("/b/no-such-" + tag)).andExpect(status().isNotFound());
        mvc.perform(get("/ical/nosuch" + tag + ".ics")).andExpect(status().isNotFound());

        // открытая сессия хозяина больше не работает
        assertThat(mvc.perform(get("/settings").session(session)).andReturn().getResponse().getRedirectedUrl())
                .contains("/login");

        // журнал: «аккаунт удалён по запросу», числа, без адресов и имён; хеши кодов — без самих кодов
        String details = jdbc.queryForObject("SELECT details FROM admin_audit_log WHERE action = 'USER_DELETE'"
                + " AND target_id = ? AND result = 'OK'", String.class, s.userId());
        assertThat(details).startsWith("аккаунт удалён по запросу").contains("объектов: 1", "броней: 1")
                .doesNotContain("@", "Дом у моря", "Хозяйка", "Удаляемый");
        List<String> stored = jdbc.queryForList("SELECT code_hash FROM deleted_public_links", String.class);
        for (String code : List.of(s.slug(), s.widgetSecret(), s.qrBooking(), s.exportSecret(), s.feedbackCode())) {
            assertThat(stored).doesNotContain(code);
        }
    }

    @Test
    @DisplayName("аккаунт с администратором не удаляется")
    void adminProtected() throws Exception {
        mvc.perform(post("/admin/users/" + admin.getUserId() + "/delete").param("confirmEmail", admin.getUsername())
                .with(user(admin)).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, admin.getUserId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT result FROM admin_audit_log WHERE action = 'USER_DELETE' AND target_id = ?"
                + " ORDER BY id DESC LIMIT 1", String.class, admin.getUserId())).isEqualTo("REFUSED");
    }
}
