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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.service.EmailService;
import ru.rentoptima.service.PropertyCreator;
import ru.rentoptima.service.WidgetCreator;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Мягкое подтверждение почты на настоящем PostgreSQL, письма — на GreenMail.
 * Нужен docker; без него тест пропускается.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "app.uploads.dir=target/test-uploads",
        "spring.mail.host=127.0.0.1",
        "app.mail.from=OptiRent <no-reply@optirent.ru>",
        "app.mail.reply-to=support@optirent.ru"})
@AutoConfigureMockMvc
@DisplayName("Подтверждение почты: плашка, страница бронирования, экран через 7 дней, смена адреса")
class EmailVerificationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final ServerSetup SMTP = new ServerSetup(3027, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);

    @RegisterExtension
    static GreenMailExtension mail = new GreenMailExtension(SMTP);

    private static final Pattern LINK = Pattern.compile("https?://[^\\s\"<]+/verify\\?token=([A-Za-z0-9_-]{43})");
    private static final String BANNER = "id=\"verify-banner\"";
    private static final String SOON = "Страница бронирования скоро заработает";
    private static final String PASSWORD = "Reg-password-1";

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
    @Autowired PropertyCreator propertyCreator;
    @Autowired WidgetCreator widgetCreator;
    @SpyBean EmailService email;
    @Autowired ru.rentoptima.service.WidgetFormToken formTokens;

    private static int registrations;

    @AfterEach
    void cleanMail() throws Exception {
        mail.purgeEmailFromAllMailboxes();
    }

    record Host(String email, Long userId, Long tenantId, MockHttpSession session) {}

    /** Регистрация через форму, как у живого человека. */
    private Host register() throws Exception {
        String address = "new-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String ip = "10.9." + (++registrations) + ".1";
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/register").with(csrf())
                        .with(r -> { r.setRemoteAddr(ip); return r; })
                        .param("formToken", formTokens.issue("register", System.currentTimeMillis() - 10_000))
                        .param("email", address).param("tenantName", "Квартира")
                        .param("password", PASSWORD).param("confirmPassword", PASSWORD)
                        .param("agreedToTerms", "on").param("agreedToConsent", "on"))
                .andExpect(redirectedUrl("/onboarding"))
                .andReturn().getRequest().getSession();
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, address);
        Long tenantId = jdbc.queryForObject("SELECT tenant_id FROM users WHERE id = ?", Long.class, userId);
        return new Host(address, userId, tenantId, session);
    }

    private BookingWidget page(Long tenantId) {
        Long propertyId = propertyCreator.createProperty(tenantId, "Квартира", null, "Выборг");
        UnitType unit = propertyCreator.createUnitType(tenantId, propertyId, "Основной", 1,
                new BigDecimal("3000"), new BigDecimal("3500"));
        return widgetCreator.create(tenantId, unit.getId(), "Квартира у моря");
    }

    private String body(MockHttpSession session, String url) throws Exception {
        var request = get(url);
        if (session != null) request.session(session);
        return mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MimeMessage awaitMail(String to, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            MimeMessage last = null;
            int n = 0;
            for (MimeMessage m : mail.getReceivedMessages()) {
                if (((InternetAddress) m.getAllRecipients()[0]).getAddress().equals(to)) {
                    n++;
                    last = m;
                }
            }
            if (n >= count) return last;
            Thread.sleep(100);
        }
        throw new AssertionError("Нет письма №" + count + " для " + to);
    }

    private static String token(MimeMessage m) throws Exception {
        String text = (String) ((jakarta.mail.Multipart) m.getContent()).getBodyPart(0).getContent();
        Matcher matcher = LINK.matcher(text);
        assertThat(matcher.find()).as("ссылка подтверждения в письме").isTrue();
        return matcher.group(1);
    }

    private String booking(String slug) throws Exception {
        return mvc.perform(post("/api/widget/" + slug + "/booking").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Регистрация: сразу в мастер, письмо со ссылкой, плашка в кабинете, но не в мастере")
    void registrationStartsSoftVerification() throws Exception {
        Host h = register();

        var row = jdbc.queryForMap("SELECT email_verified, page_needs_verified_email,"
                + " verify_deadline > now() + interval '6 days 23 hours' AS week FROM users WHERE id = ?", h.userId());
        assertThat(row).containsEntry("email_verified", false).containsEntry("page_needs_verified_email", true)
                .containsEntry("week", true);

        MimeMessage letter = awaitMail(h.email(), 1);
        assertThat(letter.getSubject()).isEqualTo("Подтвердите почту для OptiRent");
        assertThat(letter.getHeader(EmailService.UNISENDER_HEADER)[0]).contains("\"track_links\":0");
        String stored = jdbc.queryForObject("SELECT token_hash FROM auth_tokens WHERE user_id = ? AND kind = 'VERIFY'",
                String.class, h.userId());
        assertThat(stored).isEqualTo(AuthTokenService.hash(token(letter)));

        // полоса в одну строку: «Отправить ещё раз», «Изменить адрес», ✕ — скрыть до завтра
        assertThat(body(h.session(), "/account/password")).contains(BANNER)
                .contains("class=\"verify-strip\"", "Отправить ещё раз", "Изменить адрес", "id=\"vb-close\"");
        assertThat(body(h.session(), "/onboarding")).doesNotContain(BANNER);
        // меньше суток до закрытия кабинета — скрыть полосу нельзя
        jdbc.update("UPDATE users SET verify_deadline = now() + interval '12 hours' WHERE id = ?", h.userId());
        assertThat(body(h.session(), "/account/password")).contains("verify-strip--urgent").doesNotContain("id=\"vb-close\"");
        jdbc.update("UPDATE users SET verify_deadline = now() + interval '7 days' WHERE id = ?", h.userId());
        // ответ на «Отправить письмо ещё раз» в мастере виден на его странице
        MockHttpSession s = h.session();
        var flash = mvc.perform(post("/account/verify/resend").with(csrf()).session(s).param("back", "/onboarding?step=page"))
                .andExpect(redirectedUrl("/onboarding?step=page")).andReturn().getFlashMap();
        String wizard = mvc.perform(get("/onboarding").session(s).flashAttrs(flash)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(wizard).contains("Повторить можно через");
    }

    @Test
    @DisplayName("Новая страница бронирования до подтверждения: гостю «скоро заработает», хозяину — как есть; заявки не принимаются")
    void newPageWaitsForVerification() throws Exception {
        Host h = register();
        BookingWidget w = page(h.tenantId());

        assertThat(body(null, "/b/" + w.getSlug())).contains(SOON);
        assertThat(body(null, "/b/" + w.getSlug() + "/embed")).contains(SOON);
        assertThat(body(h.session(), "/b/" + w.getSlug())).doesNotContain(SOON);
        assertThat(booking(w.getSlug())).contains("PAGE_INACTIVE");

        String design = body(h.session(), "/settings/widgets/" + w.getId() + "/design");
        assertThat(design).contains("Ссылка и код для сайта заработают после подтверждения почты");
        assertThat(design).containsPattern("data-copy=\"dz-link\"[^>]*disabled");

        // подтвердил — страница заработала
        mvc.perform(get("/verify").param("token", token(awaitMail(h.email(), 1))).session(h.session()))
                .andExpect(redirectedUrl("/calendar/grid"));
        assertThat(body(null, "/b/" + w.getSlug())).doesNotContain(SOON);
        assertThat(booking(w.getSlug())).doesNotContain("PAGE_INACTIVE");
        assertThat(body(h.session(), "/account/password")).doesNotContain(BANNER);
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE id = ?", Boolean.class,
                h.userId())).isTrue();
    }

    @Test
    @DisplayName("Аккаунт, созданный до выкладки: плашка есть, но страница бронирования работает")
    void existingAccountKeepsItsPage() throws Exception {
        Host h = register();
        jdbc.update("UPDATE users SET page_needs_verified_email = FALSE WHERE id = ?", h.userId());
        BookingWidget w = page(h.tenantId());

        assertThat(body(null, "/b/" + w.getSlug())).doesNotContain(SOON);
        assertThat(booking(w.getSlug())).doesNotContain("PAGE_INACTIVE");
        assertThat(body(h.session(), "/account/password")).contains(BANNER);
        // администратор миграцией не затронут
        assertThat(jdbc.queryForObject("SELECT email_verified FROM users WHERE username = 'admin'", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("Ссылка одноразовая; повторное письмо — не чаще раза в 2 минуты, и старая ссылка гаснет")
    void resendAndSingleUse() throws Exception {
        Host h = register();
        String first = token(awaitMail(h.email(), 1));

        mvc.perform(post("/account/verify/resend").with(csrf()).session(h.session()).param("back", "/account/password"))
                .andExpect(redirectedUrl("/account/password"))
                .andExpect(r -> assertThat((String) r.getFlashMap().get("error")).contains("Повторить можно через"));

        jdbc.update("UPDATE auth_tokens SET created_at = now() - interval '3 minutes' WHERE user_id = ?", h.userId());
        mvc.perform(post("/account/verify/resend").with(csrf()).session(h.session()).param("back", "//evil.example"))
                .andExpect(redirectedUrl("/calendar/grid"))
                .andExpect(r -> assertThat((String) r.getFlashMap().get("success")).contains("Письмо отправлено"));
        String second = token(awaitMail(h.email(), 2));
        assertThat(second).isNotEqualTo(first);

        // первая ссылка уже не действует
        assertThat(body(null, "/verify?token=" + first)).contains("Ссылка устарела");
        mvc.perform(get("/verify").param("token", second)).andExpect(redirectedUrl("/login?verified"));
        assertThat(body(null, "/verify?token=" + second)).contains("Ссылка устарела");
        // вошедший и уже подтверждённый по старой ссылке — просто в кабинет
        mvc.perform(get("/verify").param("token", second).session(h.session())).andExpect(redirectedUrl("/calendar/grid"));
    }

    @Test
    @DisplayName("Через 7 дней — экран «Подтвердите почту, чтобы продолжить»; открыты письмо, смена адреса, выход")
    void lockedAfterDeadline() throws Exception {
        Host h = register();
        BookingWidget w = page(h.tenantId());
        // зарегистрировался 8 дней назад, срок 7 дней вышел минуту назад
        jdbc.update("UPDATE users SET created_at = now() - interval '8 days',"
                + " verify_deadline = now() - interval '1 minute' WHERE id = ?", h.userId());

        mvc.perform(get("/calendar/grid").session(h.session())).andExpect(redirectedUrl("/verify-email"));
        mvc.perform(get("/settings/widgets").session(h.session())).andExpect(redirectedUrl("/verify-email"));
        mvc.perform(post("/settings/widgets/create").with(csrf()).session(h.session())).andExpect(redirectedUrl("/verify-email"));
        assertThat(body(h.session(), "/verify-email")).contains("Подтвердите почту, чтобы продолжить").contains(h.email());
        // своя страница бронирования для хозяина открывается и сейчас
        mvc.perform(get("/b/" + w.getSlug()).session(h.session())).andExpect(status().isOk());

        jdbc.update("UPDATE auth_tokens SET created_at = now() - interval '3 minutes' WHERE user_id = ?", h.userId());
        mvc.perform(post("/account/verify/resend").with(csrf()).session(h.session()).param("back", "/verify-email"))
                .andExpect(redirectedUrl("/verify-email"));
        mvc.perform(get("/verify").param("token", token(awaitMail(h.email(), 2))).session(h.session()))
                .andExpect(redirectedUrl("/calendar/grid"));
        mvc.perform(get("/settings/widgets").session(h.session())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Смена адреса до подтверждения: пароль, занятый адрес без учёта регистра, 3 раза в сутки, вход по новому адресу")
    void changeEmail() throws Exception {
        Host h = register();
        Host other = register();

        mvc.perform(post("/account/email").with(csrf()).session(h.session())
                        .param("email", "fresh@example.com").param("password", "wrong-password").param("back", "/verify-email"))
                .andExpect(r -> assertThat((String) r.getFlashMap().get("error")).contains("Пароль указан неверно"));
        mvc.perform(post("/account/email").with(csrf()).session(h.session())
                        .param("email", other.email().toUpperCase()).param("password", PASSWORD))
                .andExpect(r -> assertThat((String) r.getFlashMap().get("error")).contains("уже занят"));

        String fresh = "fresh-" + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        mvc.perform(post("/account/email").with(csrf()).session(h.session())
                        .param("email", fresh.toUpperCase()).param("password", PASSWORD).param("back", "/account/password"))
                .andExpect(redirectedUrl("/account/password"))
                .andExpect(r -> assertThat((String) r.getFlashMap().get("success")).contains("Адрес изменён"));
        assertThat(jdbc.queryForMap("SELECT email, username FROM users WHERE id = ?", h.userId()))
                .containsEntry("email", fresh).containsEntry("username", fresh);
        token(awaitMail(fresh, 1));
        mvc.perform(formLogin("/login").user(fresh).password(PASSWORD)).andExpect(authenticated());

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/account/email").with(csrf()).session(h.session())
                    .param("email", "again-" + i + "-" + fresh).param("password", PASSWORD));
        }
        mvc.perform(post("/account/email").with(csrf()).session(h.session())
                        .param("email", "fourth-" + fresh).param("password", PASSWORD))
                .andExpect(r -> assertThat((String) r.getFlashMap().get("error")).contains("раз в сутки"));
    }

    @Test
    @DisplayName("Без SMTP: ни плашки, ни экрана, ни закрытой страницы")
    void withoutSmtpNothingIsEnforced() throws Exception {
        Host h = register();
        BookingWidget w = page(h.tenantId());
        jdbc.update("UPDATE users SET verify_deadline = now() - interval '1 day' WHERE id = ?", h.userId());
        when(email.smtpConfigured()).thenReturn(false);
        try {
            mvc.perform(get("/settings/widgets").session(h.session())).andExpect(status().isOk());
            assertThat(body(h.session(), "/account/password")).doesNotContain(BANNER);
            assertThat(body(null, "/b/" + w.getSlug())).doesNotContain(SOON);
            assertThat(booking(w.getSlug())).doesNotContain("PAGE_INACTIVE");
        } finally {
            when(email.smtpConfigured()).thenCallRealMethod();
        }
    }
}
