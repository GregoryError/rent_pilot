package ru.rentoptima.auth;

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
import ru.rentoptima.service.WidgetFormToken;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** APP_SERVICE_LOCK_OVERRIDE=open: вход и регистрация открыты, хотя в настройке закрыто. Нужен docker. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "app.service.lock-override=open")
@AutoConfigureMockMvc
@DisplayName("Сервис открыт: аварийный выход APP_SERVICE_LOCK_OVERRIDE=open")
class ServiceLockOverrideIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String PASSWORD = "Длинный-пароль-123";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WidgetFormToken formTokens;

    @Test
    @DisplayName("в базе всё закрыто — хозяин входит и регистрируется")
    void overrideOpens() throws Exception {
        jdbc.update("UPDATE service_settings SET value = 'false' WHERE key IN ('registration_open', 'login_open')");
        String tag = UUID.randomUUID().toString().substring(0, 8);
        long t = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Х', ?) RETURNING id", Long.class, "ov-" + tag);
        String login = "ov-" + tag + "@example.com";
        jdbc.update("INSERT INTO users(tenant_id, username, password_hash, role, email, email_verified)"
                + " VALUES (?, ?, ?, 'OWNER', ?, TRUE)", t, login, new BCryptPasswordEncoder().encode(PASSWORD), login);

        assertThat(mvc.perform(formLogin("/login").user(login).password(PASSWORD)).andReturn().getResponse()
                .getRedirectedUrl()).isEqualTo("/calendar/grid");
        assertThat(mvc.perform(get("/register")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("service-notice");

        String email = "ov-new-" + tag + "@example.com";
        mvc.perform(post("/register").with(csrf()).with(r -> { r.setRemoteAddr("10.55.0.1"); return r; })
                .param("email", email).param("tenantName", "Квартира")
                .param("password", PASSWORD).param("confirmPassword", PASSWORD)
                .param("agreedToTerms", "on").param("agreedToConsent", "on")
                .param("formToken", formTokens.issue("register", System.currentTimeMillis() - 10_000)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, email)).isEqualTo(1);
    }
}
