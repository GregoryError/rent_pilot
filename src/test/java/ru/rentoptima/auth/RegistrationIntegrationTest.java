package ru.rentoptima.auth;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.security.TenantUserDetails;
import ru.rentoptima.service.EmailService;
import ru.rentoptima.service.TelegramService;
import ru.rentoptima.service.WidgetFormToken;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Регистрация: защита от ботов, уникальность без учёта регистра, режимы SOFT / STRICT / AUTO и
 * страница администратора. PostgreSQL в контейнере, письма — GreenMail. Без docker пропускается.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "app.uploads.dir=target/test-uploads",
        "spring.mail.host=127.0.0.1",
        "app.mail.from=OptiRent <no-reply@optirent.ru>"})
@AutoConfigureMockMvc
@DisplayName("Регистрация: защита, уникальность, режимы подтверждения почты")
class RegistrationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final ServerSetup SMTP = new ServerSetup(3028, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);

    @RegisterExtension
    static GreenMailExtension mail = new GreenMailExtension(SMTP);

    private static final String PASSWORD = "Reg-password-1";
    private static final Pattern LINK = Pattern.compile("/verify\\?token=([A-Za-z0-9_-]{43})");
    private static int ips;

    @TestConfiguration
    static class TestMail {
        @Bean
        @Primary
        JavaMailSender greenMailSender() {
            JavaMailSenderImpl s = new JavaMailSenderImpl();
            s.setHost("127.0.0.1");
            s.setPort(SMTP.getPort());
            return s;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WidgetFormToken formTokens;
    @Autowired RegistrationMode mode;
    @Autowired ru.rentoptima.service.PropertyCreator propertyCreator;
    @MockBean TelegramService telegram;
    @SpyBean EmailService email;

    @AfterEach
    void reset() throws Exception {
        mode.configure(RegistrationMode.Mode.AUTO, RegistrationMode.DEFAULT_THRESHOLD);
        mail.purgeEmailFromAllMailboxes();
    }

    private static String freshIp() {
        return "10.30." + (++ips) + ".1";
    }

    private static String address() {
        return "reg-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private String token(long ageMillis) {
        return formTokens.issue("register", System.currentTimeMillis() - ageMillis);
    }

    private ResultActions register(String address, String ip, String formToken, String honeypot, String password)
            throws Exception {
        var request = post("/register").with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; })
                .param("email", address).param("tenantName", "Квартира")
                .param("password", password).param("confirmPassword", password)
                .param("agreedToTerms", "on").param("agreedToConsent", "on");
        if (formToken != null) request.param("formToken", formToken);
        if (honeypot != null) request.param(ru.rentoptima.controller.RegistrationController.HONEYPOT, honeypot);
        return mvc.perform(request);
    }

    private ResultActions register(String address) throws Exception {
        return register(address, freshIp(), token(10_000), null, PASSWORD);
    }

    private String formError(ResultActions r) throws Exception {
        return r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private boolean exists(String address) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(email) = lower(?)", Integer.class, address) > 0;
    }

    @Test
    @DisplayName("Ловушка и время заполнения: бот не регистрируется, человек — да")
    void honeypotAndFillTime() throws Exception {
        String a = address();
        assertThat(formError(register(a, freshIp(), token(10_000), "https://spam.example", PASSWORD)))
                .contains("Не получилось зарегистрироваться");
        assertThat(exists(a)).isFalse();

        assertThat(formError(register(a, freshIp(), token(500), null, PASSWORD))).contains("слишком быстро");
        assertThat(formError(register(a, freshIp(), null, null, PASSWORD))).contains("Не получилось зарегистрироваться");
        assertThat(formError(register(a, freshIp(), "123.forged", null, PASSWORD))).contains("Не получилось зарегистрироваться");
        assertThat(formError(register(a, freshIp(), token(13 * 3_600_000L), null, PASSWORD))).contains("слишком долго");
        assertThat(exists(a)).isFalse();

        // форма с ошибкой выдаёт новый токен и снова содержит ловушку
        String page = mvc.perform(get("/register")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("name=\"formToken\"").contains("name=\"company_site\"").contains(PasswordPolicy.RULES);

        register(a).andExpect(redirectedUrl("/onboarding"));
        assertThat(exists(a)).isTrue();
    }

    @Test
    @DisplayName("Не больше 5 регистраций с одного IP в час")
    void ipLimit() throws Exception {
        String ip = freshIp();
        for (int i = 0; i < 5; i++) register(address(), ip, token(10_000), null, PASSWORD).andExpect(redirectedUrl("/onboarding"));
        String sixth = address();
        assertThat(formError(register(sixth, ip, token(10_000), null, PASSWORD))).contains("Слишком много регистраций");
        assertThat(exists(sixth)).isFalse();
        register(sixth).andExpect(redirectedUrl("/onboarding"));
    }

    @Test
    @DisplayName("Одноразовые домены и правила пароля")
    void disposableDomainsAndPasswordRules() throws Exception {
        for (String bad : new String[]{"bot@formtests.info", "x@MAILINATOR.com", "y@inbox.yopmail.com"}) {
            assertThat(formError(register(bad))).contains("одноразовой почты");
            assertThat(exists(bad)).isFalse();
        }
        assertThat(EmailDomains.size()).isGreaterThan(100);
        assertThat(EmailDomains.disposable("host@gmail.com")).isFalse();
        assertThat(EmailDomains.disposable("host@yandex.ru")).isFalse();

        String a = address();
        assertThat(formError(register(a, freshIp(), token(10_000), null, "Short-1"))).contains("короче 10");
        assertThat(formError(register(a, freshIp(), token(10_000), null, a.toUpperCase()))).contains("не должен совпадать");
        assertThat(exists(a)).isFalse();
    }

    @Test
    @DisplayName("Логин и адрес уникальны без учёта регистра; вход — без учёта регистра")
    void caseInsensitiveUniqueness() throws Exception {
        String a = "Case-" + UUID.randomUUID().toString().substring(0, 6) + "@Example.com";
        register(a).andExpect(redirectedUrl("/onboarding"));
        assertThat(jdbc.queryForObject("SELECT username FROM users WHERE lower(email) = lower(?)", String.class, a))
                .isEqualTo(a.toLowerCase());

        assertThat(formError(register(a.toUpperCase()))).contains("уже зарегистрирован");
        mvc.perform(formLogin("/login").user(a.toUpperCase()).password(PASSWORD)).andExpect(authenticated());

        // и сама база не пустит второй такой логин или адрес
        Long tenant = jdbc.queryForObject("SELECT tenant_id FROM users WHERE lower(email) = lower(?)", Long.class, a);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users(tenant_id, username, password_hash, role) VALUES (?, ?, 'x', 'OWNER')",
                tenant, a.toUpperCase())).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users(tenant_id, username, email, password_hash, role)"
                + " VALUES (?, 'other-login', ?, 'x', 'OWNER')", tenant, a.toUpperCase())).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("STRICT: после регистрации — «Проверьте почту», кабинет открывается по ссылке")
    void strictMode() throws Exception {
        mode.configure(RegistrationMode.Mode.STRICT, 100);
        String a = address();
        MockHttpSession session = (MockHttpSession) register(a).andExpect(redirectedUrl("/verify-email"))
                .andReturn().getRequest().getSession();

        mvc.perform(get("/calendar/grid").session(session)).andExpect(redirectedUrl("/verify-email"));
        mvc.perform(get("/onboarding").session(session)).andExpect(redirectedUrl("/verify-email"));
        String screen = mvc.perform(get("/verify-email").session(session)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(screen).contains("Проверьте почту").contains(a);

        mvc.perform(get("/verify").param("token", verifyToken(a)).session(session)).andExpect(redirectedUrl("/calendar/grid"));
        // кабинет открыт — дальше мастер первой настройки
        mvc.perform(get("/calendar/grid").session(session)).andExpect(redirectedUrl("/onboarding"));
    }

    private String verifyToken(String to) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (MimeMessage m : mail.getReceivedMessages()) {
                if (((InternetAddress) m.getAllRecipients()[0]).getAddress().equalsIgnoreCase(to)) {
                    String text = (String) ((jakarta.mail.Multipart) m.getContent()).getBodyPart(0).getContent();
                    Matcher matcher = LINK.matcher(text);
                    if (matcher.find()) return matcher.group(1);
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Нет письма для " + to);
    }

    @Test
    @DisplayName("SOFT и AUTO ниже порога: сразу в мастер")
    void softAndAutoBelowThreshold() throws Exception {
        mode.configure(RegistrationMode.Mode.SOFT, 100);
        register(address()).andExpect(redirectedUrl("/onboarding"));
        mode.configure(RegistrationMode.Mode.AUTO, 1_000);
        register(address()).andExpect(redirectedUrl("/onboarding"));
        assertThat(mode.configured()).isEqualTo(RegistrationMode.Mode.AUTO);
    }

    private Long tenant() {
        return jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Тест', ?) RETURNING id", Long.class,
                "rm-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private void account(Long tenant, boolean active, boolean verifiedByLink, String role) {
        String login = "u-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        jdbc.update("INSERT INTO users(tenant_id, username, email, password_hash, role, active, email_verified, email_verified_at)"
                        + " VALUES (?, ?, ?, 'x', ?, ?, ?, " + (verifiedByLink ? "now()" : "NULL") + ")",
                tenant, login, login, role, active, verifiedByLink);
    }

    @Test
    @DisplayName("AUTO: настоящие — подтвердившие почту или создавшие объект; на пороге — STRICT и Telegram администратору")
    void autoSwitchesOnThreshold() throws Exception {
        Long adminTenant = jdbc.queryForObject("SELECT tenant_id FROM users WHERE role = 'ADMIN' ORDER BY id LIMIT 1", Long.class);
        when(telegram.isConfigured(adminTenant)).thenReturn(true);
        clearInvocations(telegram);
        long before = mode.counts().real();
        mode.configure(RegistrationMode.Mode.AUTO, (int) before + 2);

        // не считаются: бот без объекта, отключённый (даже подтверждённый), ещё один администратор
        account(tenant(), true, false, "OWNER");
        account(tenant(), false, true, "OWNER");
        account(tenant(), true, true, "ADMIN");
        assertThat(mode.counts().real()).isEqualTo(before);
        assertThat(mode.checkThreshold()).isEqualTo(RegistrationMode.Mode.AUTO);

        // считаются: неподтверждённый, но с объектом; подтвердивший почту
        Long withProperty = tenant();
        account(withProperty, true, false, "OWNER");
        propertyCreator.createProperty(withProperty, "Квартира", null, "Выборг");
        assertThat(mode.counts().real()).isEqualTo(before + 1);
        assertThat(mode.checkThreshold()).isEqualTo(RegistrationMode.Mode.AUTO);
        verify(telegram, never()).send(anyLong(), anyString());

        account(tenant(), true, true, "OWNER");
        assertThat(mode.checkThreshold()).isEqualTo(RegistrationMode.Mode.STRICT);
        assertThat(mode.configured()).isEqualTo(RegistrationMode.Mode.STRICT);
        verify(telegram).send(eq(adminTenant), contains("STRICT"));

        register(address()).andExpect(redirectedUrl("/verify-email"));
    }

    @Test
    @DisplayName("Без SMTP режим всегда мягкий")
    void withoutSmtpAlwaysSoft() throws Exception {
        mode.configure(RegistrationMode.Mode.STRICT, 100);
        when(email.smtpConfigured()).thenReturn(false);
        try {
            assertThat(mode.forNewUser()).isEqualTo(RegistrationMode.Mode.SOFT);
            register(address()).andExpect(redirectedUrl("/onboarding"));
        } finally {
            when(email.smtpConfigured()).thenCallRealMethod();
        }
    }

    private TenantUserDetails principal(String role) {
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE role = ? ORDER BY id LIMIT 1", Long.class, role);
        Long tenantId = jdbc.queryForObject("SELECT tenant_id FROM users WHERE id = ?", Long.class, id);
        // Администратор из V1 был до правовых документов: V43 просит у него согласия заново.
        // Здесь проверяется не окно согласий, а страница администратора
        jdbc.update("UPDATE users SET legal_reconsent_required = FALSE WHERE id = ?", id);
        Tenant t = new Tenant();
        t.setId(tenantId);
        User u = new User();
        u.setId(id);
        u.setTenant(t);
        u.setUsername("p-" + id);
        u.setPasswordHash("x");
        u.setRole(User.Role.valueOf(role));
        return new TenantUserDetails(u);
    }

    @Test
    @DisplayName("Страница администратора: сводка и переключатель; хозяину закрыта")
    void adminPage() throws Exception {
        register(address());
        String page = mvc.perform(get("/admin/registration").with(user(principal("ADMIN"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("Пользователей всего").contains("Настоящих").contains("Не подтвердили почту")
                .contains("Отключены").contains("value=\"AUTO\"").doesNotContain("Почта не настроена");

        mvc.perform(post("/admin/registration").with(user(principal("ADMIN"))).with(csrf())
                        .param("mode", "STRICT").param("threshold", "250"))
                .andExpect(redirectedUrl("/admin/registration"));
        assertThat(mode.configured()).isEqualTo(RegistrationMode.Mode.STRICT);
        assertThat(mode.threshold()).isEqualTo(250);

        // Хозяину раздела нет: 404 (AdminGateFilter)
        mvc.perform(get("/admin/registration").with(user(principal("OWNER")))).andExpect(status().isNotFound());
        mvc.perform(post("/admin/registration").with(user(principal("OWNER"))).with(csrf()).param("mode", "SOFT"))
                .andExpect(status().isNotFound());
        assertThat(mode.configured()).isEqualTo(RegistrationMode.Mode.STRICT);

        when(email.smtpConfigured()).thenReturn(false);
        try {
            assertThat(mvc.perform(get("/admin/registration").with(user(principal("ADMIN")))).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8)).contains("Почта не настроена");
        } finally {
            when(email.smtpConfigured()).thenCallRealMethod();
        }
    }
}
