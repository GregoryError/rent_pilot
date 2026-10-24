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
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.security.TenantUserDetails;

import java.nio.charset.StandardCharsets;
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
 * Действия администратора с аккаунтом: отключение (с публичными страницами и без), включение,
 * защита аккаунта администратора, почта, выгрузка адресов. Каждое — в журнале без
 * персональных данных. Нужен docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Админка: действия с пользователями")
class AdminUserActionsIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String PASSWORD = "Длинный-пароль-123";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private TenantUserDetails admin;
    private int seq;
    private long adminTenant;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        adminTenant = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Админ', ?) RETURNING id",
                Long.class, "aa-admin-" + tag);
        Long id = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role, email, email_verified)"
                        + " VALUES (?, ?, ?, 'ADMIN', ?, TRUE) RETURNING id", Long.class, adminTenant, "aa-admin-" + tag,
                new BCryptPasswordEncoder().encode(PASSWORD), "aa-admin-" + tag + "@example.com");
        Tenant t = new Tenant();
        t.setId(adminTenant);
        User u = new User();
        u.setId(id);
        u.setTenant(t);
        u.setUsername("aa-admin-" + tag);
        u.setPasswordHash("x");
        u.setRole(User.Role.ADMIN);
        admin = new TenantUserDetails(u);
    }

    record Owner(long userId, long tenantId, String login, String slug, String qr) {}

    private Owner owner(boolean verified) {
        String n = String.valueOf(++seq);
        long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Хозяин', ?) RETURNING id",
                Long.class, "aa-" + tag + n);
        String login = "aa-owner-" + tag + "-" + n + "@example.com";
        long userId = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role, email, email_verified)"
                        + " VALUES (?, ?, ?, 'OWNER', ?, ?) RETURNING id", Long.class, tenantId, login,
                new BCryptPasswordEncoder().encode(PASSWORD), login, verified);
        Long propertyId = jdbc.queryForObject("INSERT INTO properties(tenant_id, name, feedback_code, housekeeper_code)"
                + " VALUES (?, 'Дом', ?, ?) RETURNING id", Long.class, tenantId, "aafb" + tag + n, "aahk" + tag + n);
        Long unitTypeId = jdbc.queryForObject("INSERT INTO unit_types(tenant_id, property_id, name, unit_count, base_price)"
                + " VALUES (?, ?, 'Дом', 1, 5000) RETURNING id", Long.class, tenantId, propertyId);
        Long channelId = jdbc.queryForObject("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled)"
                + " VALUES (?, ?, 'WIDGET', 'Страница', FALSE) RETURNING id", Long.class, tenantId, unitTypeId);
        String slug = "aa-" + tag + "-" + n;
        Long widgetId = jdbc.queryForObject("INSERT INTO booking_widgets(tenant_id, channel_id, unit_type_id, secret, slug, title)"
                + " VALUES (?, ?, ?, ?, ?, 'Дом у моря') RETURNING id", Long.class, tenantId, channelId, unitTypeId,
                "aasecret" + tag + n, slug);
        String qr = (n + tag + "qqqqqqqq").substring(0, 7);
        jdbc.update("INSERT INTO widget_qr_links(tenant_id, widget_id, code, kind) VALUES (?, ?, ?, 'BOOKING')",
                tenantId, widgetId, qr);
        return new Owner(userId, tenantId, login, slug, qr);
    }

    private MockHttpSession login(String login) throws Exception {
        MvcResult r = mvc.perform(formLogin("/login").user(login).password(PASSWORD)).andReturn();
        assertThat(r.getResponse().getRedirectedUrl()).doesNotContain("error");
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private Map<String, Object> lastAudit(String action, long target) {
        return jdbc.queryForMap("SELECT result, details, admin_user_id FROM admin_audit_log WHERE action = ?"
                + " AND target_type = 'user' AND target_id = ? ORDER BY id DESC LIMIT 1", action, target);
    }

    @Test
    @DisplayName("отключение: вход запрещён, сессия завершена, страница бронирования работает; включение возвращает вход")
    void disableKeepsPublicPages() throws Exception {
        Owner o = owner(true);
        MockHttpSession session = login(o.login());
        mvc.perform(get("/settings").session(session)).andExpect(status().isOk());

        mvc.perform(post("/admin/users/" + o.userId() + "/disable").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, o.userId())).isFalse();
        // открытая сессия завершена
        assertThat(mvc.perform(get("/settings").session(session)).andReturn().getResponse().getRedirectedUrl())
                .contains("/login");
        // войти заново нельзя
        assertThat(mvc.perform(formLogin("/login").user(o.login()).password(PASSWORD)).andReturn().getResponse()
                .getRedirectedUrl()).contains("error");
        // страница бронирования и короткая ссылка работают
        mvc.perform(get("/b/" + o.slug())).andExpect(status().isOk());
        mvc.perform(get("/api/widget/" + o.slug() + "/config")).andExpect(status().isOk());
        mvc.perform(get("/q/" + o.qr())).andExpect(status().isFound());
        Map<String, Object> a = lastAudit("USER_DISABLE", o.userId());
        assertThat(a.get("result")).isEqualTo("OK");
        assertThat((String) a.get("details")).contains("сессий завершено: 1").doesNotContain(o.login());
        assertThat(((Number) a.get("admin_user_id")).longValue()).isEqualTo(admin.getUserId());

        mvc.perform(post("/admin/users/" + o.userId() + "/enable").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        login(o.login());
        assertThat(lastAudit("USER_ENABLE", o.userId()).get("result")).isEqualTo("OK");
    }

    @Test
    @DisplayName("отключение вместе с публичными страницами: /b/ и /q/ — 410, заявки не принимаются; включение возвращает")
    void disableWithPublicPages() throws Exception {
        Owner o = owner(true);
        mvc.perform(post("/admin/users/" + o.userId() + "/disable").param("publicPages", "true")
                .with(user(admin)).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get("/b/" + o.slug())).andExpect(status().isGone());
        mvc.perform(get("/b/" + o.slug() + "/embed")).andExpect(status().isGone());
        mvc.perform(get("/q/" + o.qr())).andExpect(status().isGone());
        mvc.perform(get("/api/widget/" + o.slug() + "/config")).andExpect(status().isNotFound());
        mvc.perform(post("/api/widget/" + o.slug() + "/booking").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        assertThat((String) lastAudit("USER_DISABLE", o.userId()).get("details")).contains("и публичные страницы");

        mvc.perform(post("/admin/users/" + o.userId() + "/enable").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/b/" + o.slug())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("аккаунт с администратором не отключается — отказ в журнале")
    void adminAccountProtected() throws Exception {
        mvc.perform(post("/admin/users/" + admin.getUserId() + "/disable").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, admin.getUserId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT active FROM tenants WHERE id = ?", Boolean.class, adminTenant)).isTrue();
        assertThat(lastAudit("USER_DISABLE", admin.getUserId()).get("result")).isEqualTo("REFUSED");
        String card = mvc.perform(get("/admin/users/" + admin.getUserId()).with(user(admin)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(card).contains("В аккаунте есть администратор").doesNotContain("/disable");
    }

    @Test
    @DisplayName("почта: отметить подтверждённой; без настроенной почты письма — отказ с причиной")
    void mail() throws Exception {
        Owner o = owner(false);
        mvc.perform(post("/admin/users/" + o.userId() + "/verify-resend").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(lastAudit("USER_VERIFY_RESEND", o.userId()).get("result")).isEqualTo("REFUSED");
        mvc.perform(post("/admin/users/" + o.userId() + "/reset-link").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(lastAudit("USER_RESET_LINK", o.userId()).get("result")).isEqualTo("REFUSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_tokens WHERE user_id = ?", Integer.class, o.userId()))
                .isZero();

        mvc.perform(post("/admin/users/" + o.userId() + "/verify").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT email_verified FROM users WHERE id = ?", Boolean.class, o.userId())).isTrue();
        assertThat(lastAudit("USER_VERIFY_MANUAL", o.userId()).get("result")).isEqualTo("OK");
        // второй раз — отказ: уже подтверждена
        mvc.perform(post("/admin/users/" + o.userId() + "/verify").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(lastAudit("USER_VERIFY_MANUAL", o.userId()).get("result")).isEqualTo("REFUSED");
    }

    @Test
    @DisplayName("CSV адресов: только активные хозяева с подтверждённой почтой; в журнале — число, без адресов")
    void emailsCsv() throws Exception {
        Owner verified = owner(true);
        Owner unverified = owner(false);
        Owner off = owner(true);
        jdbc.update("UPDATE users SET username = ?, email = ? WHERE id = ?", "off-" + tag + "@example.com",
                "off-" + tag + "@example.com", off.userId());
        mvc.perform(post("/admin/users/" + off.userId() + "/disable").with(user(admin)).with(csrf()));

        MvcResult r = mvc.perform(post("/admin/users/emails.csv").with(user(admin)).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String csv = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getContentType()).startsWith("text/csv");
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("attachment");
        assertThat(csv).startsWith("email\r\n").contains(verified.login() + "\r\n")
                .doesNotContain(unverified.login()).doesNotContain("off-" + tag).doesNotContain(admin.getUsername());
        String details = jdbc.queryForObject("SELECT details FROM admin_audit_log WHERE action = 'EMAILS_EXPORT'"
                + " ORDER BY id DESC LIMIT 1", String.class);
        assertThat(details).startsWith("адресов: ").doesNotContain("@");
    }

    @Test
    @DisplayName("ячейка CSV: кавычки и формулы обезврежены")
    void csvCell() {
        assertThat(AdminUsersController.csvCell("a@b.ru")).isEqualTo("a@b.ru");
        assertThat(AdminUsersController.csvCell("=cmd@x.ru")).isEqualTo("'=cmd@x.ru");
        assertThat(AdminUsersController.csvCell("a\"b,c@x.ru")).isEqualTo("\"a\"\"b,c@x.ru\"");
    }
}
