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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.service.EmailService;
import ru.rentoptima.service.TelegramService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Восстановление и смена пароля на настоящем PostgreSQL, письма — на GreenMail.
 * Нужен docker; без него тест пропускается.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "app.uploads.dir=target/test-uploads",
        "spring.mail.host=127.0.0.1",
        "app.mail.from=OptiRent <no-reply@optirent.ru>",
        "app.mail.reply-to=support@optirent.ru"})
@AutoConfigureMockMvc
@DisplayName("Пароли: сброс по ссылке, смена, лимиты, завершение сессий")
class PasswordIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final ServerSetup SMTP = new ServerSetup(3026, "127.0.0.1", ServerSetup.PROTOCOL_SMTP);

    @RegisterExtension
    static GreenMailExtension mail = new GreenMailExtension(SMTP);

    private static final Pattern LINK = Pattern.compile("(https?://[^\\s\"<]+/reset\\?token=([A-Za-z0-9_-]{43}))");

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
    @Autowired PasswordEncoder encoder;
    @Autowired AuthTokenService tokens;
    @MockBean TelegramService telegram;
    @SpyBean EmailService email;

    private int ipCounter;

    @AfterEach
    void cleanMail() throws Exception {
        mail.purgeEmailFromAllMailboxes();
    }

    record Account(Long id, Long tenantId, String email, String password) {}

    private Account account() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Тест', ?) RETURNING id",
                Long.class, "pw-" + tag);
        String address = "host-" + tag + "@example.com";
        String password = "Old-password-1";
        Long userId = jdbc.queryForObject("INSERT INTO users(tenant_id, username, email, password_hash, display_name,"
                        + " role, email_verified) VALUES (?, ?, ?, ?, 'Хозяйка', 'OWNER', false) RETURNING id",
                Long.class, tenantId, address, address, encoder.encode(password));
        return new Account(userId, tenantId, address, password);
    }

    /** Каждому тесту — свой IP, чтобы лимиты соседних тестов не мешали. */
    private RequestPostProcessor ip(String addr) {
        return r -> {
            r.setRemoteAddr(addr);
            return r;
        };
    }

    private String freshIp() {
        return "10.0." + (++ipCounter) + ".1";
    }

    private void forgot(String address, String via, String fromIp) throws Exception {
        mvc.perform(post("/forgot").param("email", address).param("via", via).with(csrf()).with(ip(fromIp)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forgot?sent=" + via));
    }

    /** Письма уходят в фоне — ждём до 10 секунд. */
    private MimeMessage awaitMail(String to, String subjectPart) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            MimeMessage m = find(to, subjectPart);
            if (m != null) return m;
            Thread.sleep(100);
        }
        throw new AssertionError("Нет письма «" + subjectPart + "» для " + to);
    }

    private MimeMessage find(String to, String subjectPart) throws Exception {
        for (MimeMessage m : mail.getReceivedMessages()) {
            if (((InternetAddress) m.getAllRecipients()[0]).getAddress().equals(to) && m.getSubject().contains(subjectPart)) {
                return m;
            }
        }
        return null;
    }

    /** Текстовая часть письма, раскодированная. */
    private static String text(MimeMessage m) throws Exception {
        return (String) ((jakarta.mail.Multipart) m.getContent()).getBodyPart(0).getContent();
    }

    private String tokenFrom(MimeMessage m) throws Exception {
        Matcher matcher = LINK.matcher(text(m));
        assertThat(matcher.find()).as("ссылка сброса в письме").isTrue();
        assertThat(matcher.group(1)).startsWith("http://localhost/reset?token=");
        return matcher.group(2);
    }

    private MockHttpSession login(Account a, String password) throws Exception {
        return (MockHttpSession) mvc.perform(formLogin("/login").user(a.email()).password(password))
                .andExpect(authenticated())
                .andReturn().getRequest().getSession();
    }

    private void stillLoggedIn(MockHttpSession session) throws Exception {
        mvc.perform(get("/account/password").session(session)).andExpect(status().isOk());
    }

    private void loggedOut(MockHttpSession session) throws Exception {
        mvc.perform(get("/account/password").session(session)).andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    @DisplayName("Токен: в базе только SHA-256, срок, одноразовость, действует последний")
    void tokenIsHashedExpiresAndSingleUse() {
        Account a = account();
        String token = tokens.issue(a.id(), AuthTokenService.Kind.RESET, Duration.ofMinutes(60));

        assertThat(token).hasSize(43);
        String stored = jdbc.queryForObject("SELECT token_hash FROM auth_tokens WHERE user_id = ? AND used_at IS NULL",
                String.class, a.id());
        assertThat(stored).isEqualTo(AuthTokenService.hash(token)).doesNotContain(token);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_tokens WHERE token_hash = ?", Integer.class, token)).isZero();

        // открытие формы токен не тратит
        assertThat(tokens.peek(token, AuthTokenService.Kind.RESET)).contains(a.id());
        assertThat(tokens.peek(token, AuthTokenService.Kind.RESET)).contains(a.id());
        // токен другого вида не подходит
        assertThat(tokens.peek(token, AuthTokenService.Kind.VERIFY)).isEmpty();

        assertThat(tokens.consume(token, AuthTokenService.Kind.RESET)).contains(a.id());
        assertThat(tokens.consume(token, AuthTokenService.Kind.RESET)).isEmpty();

        // новая ссылка гасит прежнюю
        String first = tokens.issue(a.id(), AuthTokenService.Kind.RESET, Duration.ofMinutes(60));
        String second = tokens.issue(a.id(), AuthTokenService.Kind.RESET, Duration.ofMinutes(60));
        assertThat(tokens.peek(first, AuthTokenService.Kind.RESET)).isEmpty();
        assertThat(tokens.peek(second, AuthTokenService.Kind.RESET)).contains(a.id());

        // просроченная
        jdbc.update("UPDATE auth_tokens SET expires_at = now() - interval '1 minute' WHERE token_hash = ?",
                AuthTokenService.hash(second));
        assertThat(tokens.peek(second, AuthTokenService.Kind.RESET)).isEmpty();
        assertThat(tokens.consume(second, AuthTokenService.Kind.RESET)).isEmpty();
    }

    @Test
    @DisplayName("/forgot: ответ одинаковый, есть аккаунт или нет")
    void forgotDoesNotRevealAccount() throws Exception {
        Account a = account();
        String sameIp = freshIp();

        forgot(a.email(), "email", sameIp);
        forgot("nobody-" + UUID.randomUUID() + "@example.com", "email", sameIp);

        awaitMail(a.email(), "Новый пароль");
        String forExisting = mvc.perform(get("/forgot").param("sent", "email")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(forExisting).contains("Если такой адрес зарегистрирован");
        Thread.sleep(500);
        assertThat(mail.getReceivedMessages()).hasSize(1);
    }

    @Test
    @DisplayName("Лимиты: 3 письма на адрес в час, 10 запросов с IP в час — сверх лимита тот же ответ")
    void limits() throws Exception {
        Account a = account();
        for (int i = 0; i < 5; i++) forgot(a.email(), "email", freshIp());
        awaitMail(a.email(), "Новый пароль");
        Thread.sleep(800);
        assertThat(mail.getReceivedMessages()).hasSize(3);

        mail.purgeEmailFromAllMailboxes();
        Account b = account();
        String busyIp = freshIp();
        for (int i = 0; i < 10; i++) forgot("nobody-" + i + "-" + UUID.randomUUID() + "@example.com", "email", busyIp);
        forgot(b.email(), "email", busyIp);
        Thread.sleep(800);
        assertThat(mail.getReceivedMessages()).isEmpty();
        // с другого IP тот же адрес проходит
        forgot(b.email(), "email", freshIp());
        awaitMail(b.email(), "Новый пароль");
    }

    @Test
    @DisplayName("Сброс по ссылке: требования, одноразовость, подтверждение почты, письмо «Пароль изменён», сессии")
    void resetByLink() throws Exception {
        Account a = account();
        MockHttpSession other = login(a, a.password());
        stillLoggedIn(other);

        forgot(a.email(), "email", freshIp());
        MimeMessage letter = awaitMail(a.email(), "Новый пароль");
        assertThat(letter.getHeader(EmailService.UNISENDER_HEADER)[0]).contains("\"track_links\":0");
        assertThat(((InternetAddress) letter.getReplyTo()[0]).getAddress()).isEqualTo("support@optirent.ru");
        String token = tokenFrom(letter);

        // форма открывается сколько угодно раз и показывает требования сразу
        for (int i = 0; i < 2; i++) {
            String form = mvc.perform(get("/reset").param("token", token)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(form).contains(PasswordPolicy.RULES).contains("name=\"confirmPassword\"");
        }

        String shortPw = mvc.perform(post("/reset").param("token", token).param("password", "short")
                        .param("confirmPassword", "short").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(shortPw).contains("Пароль короче 10 символов");
        String sameAsEmail = mvc.perform(post("/reset").param("token", token).param("password", a.email().toUpperCase())
                        .param("confirmPassword", a.email().toUpperCase()).with(csrf()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(sameAsEmail).contains("не должен совпадать с адресом почты");
        String mismatch = mvc.perform(post("/reset").param("token", token).param("password", "New-password-22")
                        .param("confirmPassword", "New-password-23").with(csrf()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(mismatch).contains("Пароли не совпадают");

        mvc.perform(post("/reset").param("token", token).param("password", "New-password-22")
                        .param("confirmPassword", "New-password-22").with(csrf()))
                .andExpect(redirectedUrl("/login?reset"));

        loggedOut(other);
        login(a, "New-password-22");
        assertThat(jdbc.queryForObject("SELECT email_verified FROM users WHERE id = ?", Boolean.class, a.id())).isTrue();

        // второй раз ссылка не работает
        String again = mvc.perform(get("/reset").param("token", token)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(again).contains("Ссылка устарела или уже использована");
        mvc.perform(post("/reset").param("token", token).param("password", "Another-pass-33")
                .param("confirmPassword", "Another-pass-33").with(csrf())).andExpect(status().isOk());
        login(a, "New-password-22");

        MimeMessage changed = awaitMail(a.email(), "Пароль OptiRent изменён");
        assertThat(text(changed)).contains("ответьте на это письмо");
        assertThat(((InternetAddress) changed.getReplyTo()[0]).getAddress()).isEqualTo("support@optirent.ru");
    }

    @Test
    @DisplayName("Смена пароля в кабинете: текущий пароль, остальные сессии завершаются, текущая остаётся")
    void changePassword() throws Exception {
        Account a = account();
        MockHttpSession here = login(a, a.password());
        MockHttpSession other = login(a, a.password());

        String wrong = mvc.perform(post("/account/password").session(here).with(csrf())
                        .param("currentPassword", "nope-nope-nope").param("password", "New-password-44")
                        .param("confirmPassword", "New-password-44"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(wrong).contains("Текущий пароль указан неверно");
        stillLoggedIn(other);

        mvc.perform(post("/account/password").session(here).with(csrf())
                        .param("currentPassword", a.password()).param("password", "New-password-44")
                        .param("confirmPassword", "New-password-44"))
                .andExpect(redirectedUrl("/account/password"));

        stillLoggedIn(here);
        loggedOut(other);
        login(a, "New-password-44");
        awaitMail(a.email(), "Пароль OptiRent изменён");
    }

    @Test
    @DisplayName("Ссылка в Telegram — через бот тенанта; ответ тот же, что и без аккаунта")
    void resetViaTelegram() throws Exception {
        Account a = account();
        when(telegram.isConfigured(a.tenantId())).thenReturn(true);
        when(telegram.send(anyLong(), anyString())).thenReturn(new TelegramService.SendResult(true, null));

        forgot(a.email(), "telegram", freshIp());
        forgot("nobody-" + UUID.randomUUID() + "@example.com", "telegram", freshIp());

        verify(telegram, timeout(5000)).send(eq(a.tenantId()), argThat(t -> LINK.matcher(t).find()));
        Thread.sleep(300);
        assertThat(mail.getReceivedMessages()).isEmpty();

        // Telegram не подключён — ничего не уходит, ответ тот же
        Account b = account();
        forgot(b.email(), "telegram", freshIp());
        verify(telegram, after(500).never()).send(eq(b.tenantId()), anyString());
    }

    @Test
    @DisplayName("Без SMTP: честное «временно недоступно», письма не уходят, Telegram остаётся")
    void withoutSmtp() throws Exception {
        when(email.smtpConfigured()).thenReturn(false);
        try {
            Account a = account();
            String page = mvc.perform(get("/forgot")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(page).contains("Восстановление по почте временно недоступно")
                    .contains("Прислать ссылку в Telegram")
                    .doesNotContain("Прислать ссылку на почту");

            forgot(a.email(), "email", freshIp());
            Thread.sleep(500);
            assertThat(mail.getReceivedMessages()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_tokens WHERE user_id = ?", Integer.class, a.id()))
                    .isZero();
        } finally {
            when(email.smtpConfigured()).thenCallRealMethod();
        }
    }
}
