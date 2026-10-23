package ru.rentoptima.admin;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.security.TenantUserDetails;
import ru.rentoptima.service.WidgetFormToken;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Раздел «Пользователи»: в списке и карточке есть сведения об аккаунте и нет данных гостей,
 * хешей, токенов и секретов; поиск, фильтры, сортировка; последний вход и источник регистрации.
 * Нужен docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Админка: пользователи")
class AdminUsersIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String PASSWORD = "Длинный-пароль-123";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WidgetFormToken formTokens;

    private TenantUserDetails admin;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Админ', ?) RETURNING id",
                Long.class, "au-admin-" + tag);
        Long id = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role)"
                + " VALUES (?, ?, 'x', 'ADMIN') RETURNING id", Long.class, tenantId, "au-admin-" + tag);
        Tenant t = new Tenant();
        t.setId(tenantId);
        User u = new User();
        u.setId(id);
        u.setTenant(t);
        u.setUsername("au-admin-" + tag);
        u.setPasswordHash("x");
        u.setRole(User.Role.ADMIN);
        admin = new TenantUserDetails(u);
    }

    /** Хозяин со всем, чего в админке быть не должно. Возвращает id пользователя. */
    private long ownerWithSecrets(Map<String, String> secrets) {
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES (?, ?) RETURNING id",
                Long.class, "Аккаунт " + tag, "au-" + tag);
        String hash = "$2a$10$HASHSECRET" + tag + "abcdefghijklmnopqrstuvwxyzABCDEFGH";
        long userId = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, display_name, role,"
                + " email, tg_chat_id, email_verified, consent_version, terms_version, privacy_version,"
                + " agreed_to_consent_at, agreed_to_terms_at) VALUES (?, ?, ?, 'Хозяин Тест', 'OWNER', ?, ?, TRUE, 1, 1, 1,"
                + " now(), now()) RETURNING id", Long.class, tenantId, "owner-" + tag + "@example.com", hash,
                "owner-" + tag + "@example.com", "TGCHAT" + tag);
        secrets.put("hash", hash);
        secrets.put("tg", "TGCHAT" + tag);
        Long propertyId = jdbc.queryForObject("INSERT INTO properties(tenant_id, name, feedback_code, housekeeper_code)"
                + " VALUES (?, ?, ?, ?) RETURNING id", Long.class, tenantId, "Квартира на Садовой " + tag,
                "FBCODE" + tag, "HKCODE" + tag);
        secrets.put("housekeeper", "HKCODE" + tag);
        Long unitTypeId = jdbc.queryForObject("INSERT INTO unit_types(tenant_id, property_id, name, unit_count, base_price)"
                + " VALUES (?, ?, 'Студия', 1, 3000) RETURNING id", Long.class, tenantId, propertyId);
        jdbc.update("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled, export_secret,"
                        + " config_json, last_error, last_sync_at) VALUES (?, ?, 'ICAL', 'Циан', TRUE, ?, ?::jsonb, ?, now())",
                tenantId, unitTypeId, "EXPORTSECRET" + tag,
                "{\"import_url\": \"https://cian.ru/ical/IMPORTTOKEN" + tag + ".ics\", \"platform\": \"cian\"}",
                "fetch failed https://cian.ru/ical/ERRTOKEN" + tag);
        secrets.put("export", "EXPORTSECRET" + tag);
        secrets.put("import", "IMPORTTOKEN" + tag);
        secrets.put("error", "ERRTOKEN" + tag);
        Long widgetChannel = jdbc.queryForObject("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name,"
                + " sync_enabled) VALUES (?, ?, 'WIDGET', 'Страница', FALSE) RETURNING id", Long.class, tenantId, unitTypeId);
        jdbc.update("INSERT INTO booking_widgets(tenant_id, channel_id, unit_type_id, secret, slug, title)"
                + " VALUES (?, ?, ?, ?, ?, 'Садовая напрямую')", tenantId, widgetChannel, unitTypeId,
                "WIDGETSECRET" + tag, "sadovaya-" + tag);
        secrets.put("widget", "WIDGETSECRET" + tag);
        LocalDate in = LocalDate.now().plusDays(10);
        Long bookingId = jdbc.queryForObject("INSERT INTO bookings(tenant_id, property_id, channel_id, unit_type_id, status,"
                        + " guest_name, guest_phone, guest_email, notes, check_in, check_out, nights)"
                        + " VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, 2) RETURNING id", Long.class,
                tenantId, propertyId, widgetChannel, unitTypeId, "ГостьИмя" + tag, "+7999" + tag.hashCode() % 10000000,
                "guest-" + tag + "@mail.example", "ГОСТЕВОЙКОММЕНТАРИЙ" + tag, in, in.plusDays(2));
        secrets.put("guest", "ГостьИмя" + tag);
        secrets.put("guestEmail", "guest-" + tag + "@mail.example");
        secrets.put("note", "ГОСТЕВОЙКОММЕНТАРИЙ" + tag);
        jdbc.update("INSERT INTO feedback_responses(property_id, booking_id, session_id, guest_name, guest_phone, completed)"
                + " VALUES (?, ?, ?, ?, ?, TRUE)", propertyId, bookingId, "SESSION" + tag, "ОтзывИмя" + tag, "+7000" + tag);
        secrets.put("session", "SESSION" + tag);
        secrets.put("reviewer", "ОтзывИмя" + tag);
        jdbc.update("INSERT INTO auth_tokens(user_id, kind, token_hash, expires_at) VALUES (?, 'RESET', ?,"
                + " now() + interval '1 hour')", userId, ("TOKENHASH" + tag + "0".repeat(64)).substring(0, 64));
        secrets.put("token", ("TOKENHASH" + tag));
        return userId;
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(url).with(user(admin))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("список и карточка: сведения об аккаунте есть; гостей, хешей, токенов и секретов нет")
    void noSecrets() throws Exception {
        Map<String, String> secrets = new java.util.HashMap<>();
        long id = ownerWithSecrets(secrets);

        String list = mvc.perform(get("/admin/users").param("q", "садовой " + tag).with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(list);
        assertThat(doc.select("a[href=/admin/users/" + id + "]").text()).isEqualTo("owner-" + tag + "@example.com");
        assertThat(list).contains("Квартира на Садовой " + tag, "заявок за 30 дней: 1", "ред. 1");

        String card = page("/admin/users/" + id);
        assertThat(card).contains("Квартира на Садовой " + tag, "Циан", "Садовая напрямую", "/b/sadovaya-" + tag,
                "Студия", "заявок за 30 дней: 1");
        for (String html : List.of(list, card)) {
            for (Map.Entry<String, String> s : secrets.entrySet()) {
                assertThat(html).as(s.getKey()).doesNotContain(s.getValue());
            }
            assertThat(html).doesNotContain("$2a$");
        }
    }

    @Test
    @DisplayName("фильтры, сортировка и поиск по адресу")
    void filters() throws Exception {
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Ф', ?) RETURNING id",
                Long.class, "auf-" + tag);
        long on = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role, email, email_verified,"
                + " last_login_at) VALUES (?, ?, 'x', 'OWNER', ?, TRUE, now()) RETURNING id", Long.class, tenantId,
                "f-on-" + tag + "@example.com", "f-on-" + tag + "@example.com");
        long off = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role, email, email_verified,"
                + " active, verify_deadline) VALUES (?, ?, 'x', 'OWNER', ?, FALSE, FALSE, now() - interval '1 day')"
                + " RETURNING id", Long.class, tenantId, "f-off-" + tag + "@example.com", "f-off-" + tag + "@example.com");

        String q = "/admin/users?q=" + tag;
        assertThat(Jsoup.parse(page(q)).select("a[href^=/admin/users/]").eachAttr("href"))
                .contains("/admin/users/" + on, "/admin/users/" + off);
        assertThat(page(q + "&status=disabled")).contains("/admin/users/" + off).doesNotContain("/admin/users/" + on + "\"");
        assertThat(page(q + "&status=active")).contains("/admin/users/" + on).doesNotContain("/admin/users/" + off + "\"");
        assertThat(page(q + "&verified=no")).contains("/admin/users/" + off, "срок истёк")
                .doesNotContain("/admin/users/" + on + "\"");
        assertThat(page(q + "&props=yes")).doesNotContain("/admin/users/" + on + "\"");
        // по последнему входу: вошедший — первым
        List<String> order = Jsoup.parse(page(q + "&sort=login")).select("td a[href^=/admin/users/]").eachAttr("href");
        assertThat(order.indexOf("/admin/users/" + on)).isLessThan(order.indexOf("/admin/users/" + off));
        // кривые параметры не ломают страницу
        page("/admin/users?sort=drop&status=x&page=-5");
        mvc.perform(get("/admin/users/999999999").with(user(admin))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("последний вход записывается при входе формой")
    void lastLogin() throws Exception {
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Вход', ?) RETURNING id",
                Long.class, "aul-" + tag);
        String login = "login-" + tag + "@example.com";
        long id = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role, email)"
                        + " VALUES (?, ?, ?, 'OWNER', ?) RETURNING id", Long.class, tenantId, login,
                new BCryptPasswordEncoder().encode(PASSWORD), login);
        mvc.perform(formLogin("/login").user(login).password(PASSWORD)).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT last_login_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, id))
                .isTrue();
    }

    @Test
    @DisplayName("источник регистрации: метки и домен перехода — из ссылки в форму и в аккаунт; свой домен не считается")
    void signupSource() throws Exception {
        String form = mvc.perform(get("/register").param("utm_source", "vk").param("utm_medium", "post")
                        .param("utm_campaign", "osen").param("ref", "www.VC.ru"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Document doc = Jsoup.parse(form);
        assertThat(doc.select("input[name=utm_source]").attr("value")).isEqualTo("vk");
        assertThat(doc.select("input[name=ref]").attr("value")).isEqualTo("vc.ru");

        // переход со своего сайта — не источник; чужой Referer — только домен
        assertThat(Jsoup.parse(mvc.perform(get("/register").header("Referer", "http://localhost/instrukcii"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).select("input[name=ref]")).isEmpty();
        assertThat(Jsoup.parse(mvc.perform(get("/register").header("Referer", "https://t.me/channel/123?x=secret"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).select("input[name=ref]")
                .attr("value")).isEqualTo("t.me");

        String email = "src-" + tag + "@example.com";
        mvc.perform(post("/register").with(csrf()).with(r -> { r.setRemoteAddr("10.77." + (tag.hashCode() & 0xff) + ".9"); return r; })
                        .param("email", email).param("tenantName", "Квартира")
                        .param("password", PASSWORD).param("confirmPassword", PASSWORD)
                        .param("agreedToTerms", "on").param("agreedToConsent", "on")
                        .param("formToken", formTokens.issue("register", System.currentTimeMillis() - 10_000))
                        .param("utm_source", "vk").param("utm_medium", "post").param("utm_campaign", "osen")
                        .param("ref", "vc.ru"))
                .andExpect(status().is3xxRedirection());
        Map<String, Object> row = jdbc.queryForMap("SELECT id, signup_utm_source, signup_utm_medium, signup_utm_campaign,"
                + " signup_referrer_domain, last_login_at FROM users WHERE username = ?", email);
        assertThat(row.get("signup_utm_source")).isEqualTo("vk");
        assertThat(row.get("signup_utm_medium")).isEqualTo("post");
        assertThat(row.get("signup_utm_campaign")).isEqualTo("osen");
        assertThat(row.get("signup_referrer_domain")).isEqualTo("vc.ru");
        assertThat(row.get("last_login_at")).isNotNull();
        assertThat(page("/admin/users/" + row.get("id"))).contains("источник vk, канал post, кампания osen, переход с vc.ru");
    }
}
