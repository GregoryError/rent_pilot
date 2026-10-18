package ru.rentoptima.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.security.TenantUserDetails;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Раздел администратора: каждый адрес /admin/** (список берётся из маппингов Spring, новые
 * страницы попадают в проверку сами) для хозяина и анонима — 404, ни одно действие не
 * выполняется; администратору страницы открываются и закрыты от поиска. Нужен docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.uploads.dir=target/test-uploads"})
@AutoConfigureMockMvc
@DisplayName("Раздел администратора: хозяину и анониму — 404 на каждый адрес")
class AdminAccessIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    private TenantUserDetails owner;
    private TenantUserDetails admin;

    /** Адрес и метод из маппинга; переменные пути — правдоподобными значениями. */
    record Endpoint(RequestMethod method, String path) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Тест', ?) RETURNING id",
                Long.class, "adm-" + tag);
        owner = details(tenantId, jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role)"
                + " VALUES (?, ?, 'x', 'OWNER') RETURNING id", Long.class, tenantId, "o-" + tag), User.Role.OWNER);
        admin = details(tenantId, jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role)"
                + " VALUES (?, ?, 'x', 'ADMIN') RETURNING id", Long.class, tenantId, "a-" + tag), User.Role.ADMIN);
    }

    private static TenantUserDetails details(Long tenantId, Long id, User.Role role) {
        Tenant t = new Tenant();
        t.setId(tenantId);
        User u = new User();
        u.setId(id);
        u.setTenant(t);
        u.setUsername("u" + id);
        u.setPasswordHash("x");
        u.setRole(role);
        return new TenantUserDetails(u);
    }

    List<Endpoint> adminEndpoints() {
        Set<String> seen = new TreeSet<>();
        List<Endpoint> out = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/admin")) continue;
                String path = pattern.replaceAll("\\{slug[^}]*}", "privacy").replaceAll("\\{[^}]+}", "1");
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    if (seen.add(m + " " + path)) out.add(new Endpoint(m, path));
                }
            }
        }
        return out;
    }

    private static MockHttpServletRequestBuilder request(Endpoint e) {
        return switch (e.method()) {
            case POST -> post(e.path()).with(csrf());
            default -> get(e.path());
        };
    }

    @Test
    @DisplayName("хозяин и аноним: каждый адрес раздела — 404, данных журнала не прибавилось")
    void ownerAndAnonymousGet404() throws Exception {
        List<Endpoint> endpoints = adminEndpoints();
        assertThat(endpoints).extracting(Endpoint::path)
                .contains("/admin/legal-pages", "/admin/registration", "/admin/onboarding", "/admin/audit");
        Integer before = jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class);
        for (Endpoint e : endpoints) {
            assertThat(mvc.perform(request(e).with(user(owner))).andReturn().getResponse().getStatus())
                    .as("хозяин: " + e).isEqualTo(404);
            assertThat(mvc.perform(request(e)).andReturn().getResponse().getStatus())
                    .as("аноним: " + e).isEqualTo(404);
        }
        assertThat(mvc.perform(get("/admin").with(user(owner))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/admin/nope")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_log", Integer.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("администратор: страницы раздела открываются и закрыты от поиска")
    void adminPagesOpenNoindex() throws Exception {
        for (Endpoint e : adminEndpoints()) {
            if (e.method() != RequestMethod.GET || e.path().contains("/1")) continue;
            var r = mvc.perform(get(e.path()).with(user(admin))).andReturn().getResponse();
            assertThat(r.getStatus()).as(e.toString()).isEqualTo(200);
            assertThat(r.getHeader("X-Robots-Tag")).as(e.toString()).isEqualTo("noindex, nofollow");
        }
    }

    @Test
    @DisplayName("журнал: действие администратора записано без персональных данных и видно на странице")
    void auditRecorded() throws Exception {
        mvc.perform(post("/admin/registration").with(user(admin)).with(csrf()).param("mode", "SOFT").param("threshold", "50"));
        String page = mvc.perform(get("/admin/audit").with(user(admin))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        String adminLogin = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, admin.getUserId());
        assertThat(page).contains("Режим регистрации", "SOFT, порог 50", "выполнено", adminLogin);
        assertThat(jdbc.queryForObject("SELECT admin_user_id FROM admin_audit_log ORDER BY id DESC LIMIT 1",
                Long.class)).isEqualTo(admin.getUserId());
    }
}
