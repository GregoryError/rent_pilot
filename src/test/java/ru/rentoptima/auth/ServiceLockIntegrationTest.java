package ru.rentoptima.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
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
import ru.rentoptima.service.WidgetFormToken;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Выключатель «Сервис открыт»: закрытая регистрация и вход проверяются на сервере, администратор
 * входит всегда, сессии хозяев гаснут, публичное (фиды, страница бронирования, заявки) работает.
 * Нужен docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Сервис открыт: выключатель входа и регистрации")
class ServiceLockIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String PASSWORD = "Длинный-пароль-123";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WidgetFormToken formTokens;

    private TenantUserDetails admin;
    private String adminLogin;
    private String ownerLogin;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        long adminTenant = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Админ', ?) RETURNING id",
                Long.class, "sl-admin-" + tag);
        adminLogin = "sl-admin-" + tag + "@example.com";
        long adminId = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role, email)"
                + " VALUES (?, ?, ?, 'ADMIN', ?) RETURNING id", Long.class, adminTenant, adminLogin, enc.encode(PASSWORD), adminLogin);
        Tenant t = new Tenant();
        t.setId(adminTenant);
        User u = new User();
        u.setId(adminId);
        u.setTenant(t);
        u.setUsername(adminLogin);
        u.setPasswordHash("x");
        u.setRole(User.Role.ADMIN);
        admin = new TenantUserDetails(u);
        long ownerTenant = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Хозяин', ?) RETURNING id",
                Long.class, "sl-owner-" + tag);
        ownerLogin = "sl-owner-" + tag + "@example.com";
        jdbc.update("INSERT INTO users(tenant_id, username, password_hash, role, email, email_verified)"
                + " VALUES (?, ?, ?, 'OWNER', ?, TRUE)", ownerTenant, ownerLogin, enc.encode(PASSWORD), ownerLogin);
    }

    @AfterEach
    void reopen() throws Exception {
        set(true, true);
    }

    private void set(boolean registration, boolean login) throws Exception {
        var req = post("/admin/service").with(user(admin)).with(csrf())
                .param("title", "Сервис на стадии запуска")
                .param("text", "Регистрация откроется в ближайшее время. [Напишите нам](mailto:host@example.com)");
        if (registration) req.param("registrationOpen", "true");
        if (login) req.param("loginOpen", "true");
        mvc.perform(req).andExpect(status().is3xxRedirection());
    }

    private String page(String url) throws Exception {
        return mvc.perform(get(url)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MvcResult register(String email) throws Exception {
        return mvc.perform(post("/register").with(csrf()).with(r -> { r.setRemoteAddr("10.66." + (email.hashCode() & 0xff) + ".1"); return r; })
                .param("email", email).param("tenantName", "Квартира")
                .param("password", PASSWORD).param("confirmPassword", PASSWORD)
                .param("agreedToTerms", "on").param("agreedToConsent", "on")
                .param("formToken", formTokens.issue("register", System.currentTimeMillis() - 10_000))).andReturn();
    }

    private int users(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(username) = lower(?)", Integer.class, email);
    }

    @Test
    @DisplayName("закрыта регистрация: форма неактивна с объявлением, POST отклонён, пользователь не создан")
    void registrationClosed() throws Exception {
        set(false, true);
        Document form = Jsoup.parse(page("/register"));
        assertThat(form.select("fieldset.service-locked[disabled]")).hasSize(1);
        assertThat(form.select(".service-notice").text()).contains("Сервис на стадии запуска", "Регистрация откроется");
        assertThat(form.select(".service-notice a[href=mailto:host@example.com]")).hasSize(1);

        String email = "closed-" + tag + "@example.com";
        MvcResult r = register(email);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("Сервис на стадии запуска");
        assertThat(users(email)).isZero();
        // вход при этом открыт
        assertThat(mvc.perform(formLogin("/login").user(ownerLogin).password(PASSWORD)).andReturn().getResponse()
                .getRedirectedUrl()).doesNotContain("error", "closed");
        assertThat(jdbc.queryForObject("SELECT details FROM admin_audit_log WHERE action = 'SERVICE_REGISTRATION'"
                + " ORDER BY id DESC LIMIT 1", String.class)).isEqualTo("закрыта");

        set(true, true);
        register("open-" + tag + "@example.com");
        assertThat(users("open-" + tag + "@example.com")).isEqualTo(1);
    }

    @Test
    @DisplayName("закрыт вход: хозяин не входит, админ входит, сессия хозяина завершена; восстановление пароля закрыто")
    void loginClosed() throws Exception {
        MockHttpSession owner = (MockHttpSession) mvc.perform(formLogin("/login").user(ownerLogin).password(PASSWORD))
                .andReturn().getRequest().getSession(false);
        mvc.perform(get("/settings").session(owner)).andExpect(status().isOk());

        set(true, false);
        assertThat(jdbc.queryForObject("SELECT details FROM admin_audit_log WHERE action = 'SERVICE_LOGIN'"
                + " ORDER BY id DESC LIMIT 1", String.class)).startsWith("закрыт; сессий хозяев завершено: ");
        // открытая сессия хозяина больше не работает
        assertThat(mvc.perform(get("/settings").session(owner)).andReturn().getResponse().getRedirectedUrl()).contains("/login");

        // хозяин не входит: объявление, а не «неверный пароль»
        assertThat(mvc.perform(formLogin("/login").user(ownerLogin).password(PASSWORD)).andReturn().getResponse()
                .getRedirectedUrl()).isEqualTo("/login?closed");
        assertThat(mvc.perform(formLogin("/login").user(ownerLogin).password("wrong-password-123")).andReturn().getResponse()
                .getRedirectedUrl()).isEqualTo("/login?closed");
        // администратор входит
        MvcResult a = mvc.perform(formLogin("/login").user(adminLogin).password(PASSWORD)).andReturn();
        assertThat(a.getResponse().getRedirectedUrl()).isEqualTo("/calendar/grid");
        mvc.perform(get("/admin/service").session((MockHttpSession) a.getRequest().getSession(false)))
                .andExpect(status().isOk());

        Document login = Jsoup.parse(page("/login"));
        assertThat(login.select("fieldset.service-locked[disabled]")).hasSize(1);
        assertThat(login.select(".service-notice").text()).contains("Сервис на стадии запуска");
        assertThat(login.select("a[href=/login?admin=1]")).hasSize(1);
        assertThat(Jsoup.parse(page("/login?admin=1")).select("fieldset.service-locked[disabled]")).isEmpty();

        // без входа нет и регистрации
        assertThat(register("nologin-" + tag + "@example.com").getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Сервис на стадии запуска");
        assertThat(users("nologin-" + tag + "@example.com")).isZero();

        // /forgot, /reset, /verify — объявление; ссылка сброса не создаётся
        assertThat(page("/forgot")).contains("Сервис на стадии запуска").doesNotContain("name=\"email\"");
        mvc.perform(post("/forgot").with(csrf()).param("email", ownerLogin)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_tokens t JOIN users u ON u.id = t.user_id"
                + " WHERE u.username = ?", Integer.class, ownerLogin)).isZero();
        assertThat(page("/reset?token=abc")).contains("Сервис на стадии запуска").doesNotContain("name=\"password\"");
        assertThat(mvc.perform(post("/reset").with(csrf()).param("token", "abc").param("password", PASSWORD)
                .param("confirmPassword", PASSWORD)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Сервис на стадии запуска");
        assertThat(page("/verify?token=abc")).contains("Сервис на стадии запуска");

        set(true, true);
        assertThat(mvc.perform(formLogin("/login").user(ownerLogin).password(PASSWORD)).andReturn().getResponse()
                .getRedirectedUrl()).doesNotContain("closed", "error");
    }

    @Test
    @DisplayName("при закрытых входе и регистрации работают фид, страница бронирования и заявка через виджет")
    void publicStillWorks() throws Exception {
        long t = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Стр', ?) RETURNING id", Long.class, "sl-p-" + tag);
        long p = jdbc.queryForObject("INSERT INTO properties(tenant_id, name, feedback_code, housekeeper_code)"
                + " VALUES (?, 'Лофт', ?, ?) RETURNING id", Long.class, t, "slf" + tag, "slh" + tag);
        long ut = jdbc.queryForObject("INSERT INTO unit_types(tenant_id, property_id, name, unit_count, base_price)"
                + " VALUES (?, ?, 'Лофт', 1, 4000) RETURNING id", Long.class, t, p);
        long wch = jdbc.queryForObject("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled)"
                + " VALUES (?, ?, 'WIDGET', 'Прямая', FALSE) RETURNING id", Long.class, t, ut);
        String ical = "sl-ical-" + tag;
        jdbc.update("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled, export_secret)"
                + " VALUES (?, ?, 'ICAL', 'Суточно', FALSE, ?)", t, ut, ical);
        String slug = "sl-" + tag;
        long w = jdbc.queryForObject("INSERT INTO booking_widgets(tenant_id, channel_id, unit_type_id, secret, slug, title)"
                + " VALUES (?, ?, ?, ?, ?, 'Лофт') RETURNING id", Long.class, t, wch, ut, "sl-secret-" + tag, slug);
        jdbc.update("INSERT INTO guest_operators(tenant_id, name, contact) VALUES (?, 'Иванов И. И.', 'host@example.com')", t);
        jdbc.update("INSERT INTO public_data_consents(tenant_id, widget_id, fields, document_version)"
                + " VALUES (?, ?, 'phone,operator_name,operator_contact', 1)", t, w);

        set(false, false);
        mvc.perform(get("/ical/" + ical + ".ics")).andExpect(status().isOk());
        mvc.perform(get("/b/" + slug)).andExpect(status().isOk());
        mvc.perform(get("/legal/privacy")).andExpect(status().isOk());
        mvc.perform(get("/instrukcii")).andExpect(status().isOk());
        mvc.perform(get("/feedback/slf" + tag)).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().isOk());

        LocalDate from = LocalDate.now().plusDays(15);
        String api = "/api/widget/" + slug;
        String quote = mvc.perform(post(api + "/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkin\":\"" + from + "\",\"checkout\":\"" + from.plusDays(2) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var q = new ObjectMapper().readTree(quote);
        Thread.sleep(3_100);
        String form = "{\"checkin\":\"" + from + "\",\"checkout\":\"" + from.plusDays(2) + "\",\"adults\":1,"
                + "\"name\":\"Пётр\",\"phone\":\"+7 900 111-22-33\",\"consent\":true,\"expectedTotal\":"
                + q.path("total").asInt() + ",\"formToken\":\"" + q.path("formToken").asText() + "\"}";
        mvc.perform(post(api + "/booking").contentType(MediaType.APPLICATION_JSON).content(form))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE channel_id = ?", Integer.class, wch)).isEqualTo(1);
    }

    @Test
    @DisplayName("«Скрыть из поиска» — сразу, без публикации и миграций, с записью в журнал")
    void guideSearchToggle() throws Exception {
        long id = jdbc.queryForObject("SELECT id FROM guides WHERE slug = 'cian'", Long.class);
        try {
            mvc.perform(post("/admin/guides/" + id + "/search").param("hidden", "false").with(user(admin)).with(csrf()))
                    .andExpect(status().is3xxRedirection());
            assertThat(jdbc.queryForObject("SELECT hidden_from_search FROM guides WHERE id = ?", Boolean.class, id)).isFalse();
            assertThat(jdbc.queryForObject("SELECT details FROM admin_audit_log WHERE action = 'GUIDE_SEARCH'"
                    + " AND target_id = ? ORDER BY id DESC LIMIT 1", String.class, id)).isEqualTo("открыта для поиска");
            mvc.perform(post("/admin/guides/" + id + "/search").param("hidden", "true").with(user(admin)).with(csrf()))
                    .andExpect(status().is3xxRedirection());
            assertThat(jdbc.queryForObject("SELECT hidden_from_search FROM guides WHERE id = ?", Boolean.class, id)).isTrue();
            assertThat(jdbc.queryForObject("SELECT details FROM admin_audit_log WHERE action = 'GUIDE_SEARCH'"
                    + " AND target_id = ? ORDER BY id DESC LIMIT 1", String.class, id)).isEqualTo("скрыта из поиска");
        } finally {
            jdbc.update("UPDATE guides SET hidden_from_search = TRUE WHERE id = ?", id);
        }
    }
}
