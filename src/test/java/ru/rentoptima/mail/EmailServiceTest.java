package ru.rentoptima.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import ru.rentoptima.service.EmailService;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Письма уходят на тестовый SMTP-сервер GreenMail — настоящий Unisender не трогаем. */
class EmailServiceTest {

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(new ServerSetup[]{ServerSetupTest.SMTP, ServerSetupTest.SMTPS});

    private static final String LINK = "https://staging.optirent.ru/reset?token=abc_DEF-123";

    private final ObjectMapper json = new ObjectMapper();

    private static Mail resetMail() {
        return Mail.to("host@example.com")
                .subject("Восстановление пароля OptiRent")
                .heading("Новый пароль")
                .paragraph("Кто-то запросил сброс пароля.\nЕсли это были вы — нажмите кнопку.")
                .button("Задать новый пароль", LINK)
                .footer("Ссылка действует 60 минут.")
                .build();
    }

    private static JavaMailSenderImpl plainSender() {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost("127.0.0.1");
        s.setPort(ServerSetupTest.SMTP.getPort());
        return s;
    }

    private static EmailService service(JavaMailSender sender, String host, String replyTo, boolean skipUnsubscribe) {
        ObjectProvider<JavaMailSender> provider =
                new StaticListableBeanFactory(Map.of("mailSender", sender)).getBeanProvider(JavaMailSender.class);
        return new EmailService(provider, host, "OptiRent <no-reply@optirent.ru>", replyTo, skipUnsubscribe);
    }

    @Test
    void sendsHtmlAndTextWithReplyToAndTrackingOff() throws Exception {
        EmailService email = service(plainSender(), "127.0.0.1", "support@optirent.ru", false);

        assertThat(email.send(resetMail())).isTrue();

        MimeMessage[] received = smtp.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage m = received[0];
        assertThat(m.getSubject()).isEqualTo("Восстановление пароля OptiRent");
        assertThat(((InternetAddress) m.getFrom()[0]).getAddress()).isEqualTo("no-reply@optirent.ru");
        assertThat(((InternetAddress) m.getFrom()[0]).getPersonal()).isEqualTo("OptiRent");
        assertThat(((InternetAddress) m.getReplyTo()[0]).getAddress()).isEqualTo("support@optirent.ru");

        JsonNode header = json.readTree(m.getHeader(EmailService.UNISENDER_HEADER)[0]);
        assertThat(header.get("track_links").asInt()).isZero();
        assertThat(header.get("track_read").asInt()).isZero();
        assertThat(header.has("skip_unsubscribe")).isFalse();

        assertThat(m.getContentType()).startsWith("multipart/alternative");
        Multipart parts = (Multipart) m.getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        BodyPart text = parts.getBodyPart(0);
        BodyPart html = parts.getBodyPart(1);
        assertThat(text.getContentType()).startsWith("text/plain").containsIgnoringCase("charset=UTF-8");
        assertThat(html.getContentType()).startsWith("text/html").containsIgnoringCase("charset=UTF-8");

        String plain = (String) text.getContent();
        assertThat(plain).contains("Кто-то запросил сброс пароля.").contains(LINK);
        String body = (String) html.getContent();
        // кнопка и та же ссылка текстом под ней
        assertThat(org.springframework.util.StringUtils.countOccurrencesOf(body, "href=\"" + LINK + "\"")).isEqualTo(2);
        assertThat(body).contains(">" + LINK + "</a>").contains("Задать новый пароль");
        assertThat(body).doesNotContain("<img").doesNotContain("src=");
    }

    @Test
    void skipUnsubscribeOnlyWhenEnabled() throws Exception {
        assertThat(service(plainSender(), "127.0.0.1", "", true).send(resetMail())).isTrue();

        MimeMessage m = smtp.getReceivedMessages()[0];
        JsonNode header = json.readTree(m.getHeader(EmailService.UNISENDER_HEADER)[0]);
        assertThat(header.get("skip_unsubscribe").asInt()).isEqualTo(1);
        assertThat(header.get("track_links").asInt()).isZero();
        // без MAIL_REPLY_TO заголовка нет: клиент ответит на From
        assertThat(m.getHeader("Reply-To")).isNull();
    }

    @Test
    void withoutSmtpHostNothingIsSent() {
        EmailService email = service(plainSender(), "", "support@optirent.ru", false);

        assertThat(email.smtpConfigured()).isFalse();
        assertThat(email.send(resetMail())).isFalse();
        assertThat(smtp.getReceivedMessages()).isEmpty();
    }

    @Test
    void sslModeConnectsToSmtps() throws Exception {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost("127.0.0.1");
        s.setPort(ServerSetupTest.SMTPS.getPort());
        Properties p = MailConfig.properties(MailConfig.Security.SSL, false);
        // у GreenMail самоподписанный сертификат
        p.put("mail.smtp.ssl.trust", "*");
        p.put("mail.smtp.ssl.checkserveridentity", "false");
        s.setJavaMailProperties(p);

        assertThat(service(s, "127.0.0.1", "", false).send(resetMail())).isTrue();
        assertThat(smtp.getReceivedMessages()).hasSize(1);
    }

    @Test
    void securityFollowsVariableThenPort() {
        assertThat(MailConfig.resolve("", 587)).isEqualTo(MailConfig.Security.STARTTLS);
        assertThat(MailConfig.resolve(null, 465)).isEqualTo(MailConfig.Security.SSL);
        assertThat(MailConfig.resolve("starttls", 465)).isEqualTo(MailConfig.Security.STARTTLS);
        assertThat(MailConfig.resolve(" SSL ", 587)).isEqualTo(MailConfig.Security.SSL);
        assertThat(MailConfig.resolve("nonsense", 25)).isEqualTo(MailConfig.Security.STARTTLS);

        Properties starttls = MailConfig.properties(MailConfig.Security.STARTTLS, true);
        assertThat(starttls).containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.auth", "true")
                .doesNotContainKey("mail.smtp.ssl.enable");
        assertThat(MailConfig.properties(MailConfig.Security.SSL, true))
                .containsEntry("mail.smtp.ssl.enable", "true")
                .doesNotContainKey("mail.smtp.starttls.enable");
    }

    @Test
    void rendererEscapesText() {
        Mail mail = Mail.to("a@b.c").subject("<b>").paragraph("Гость <script>alert(1)</script> & co").build();

        String html = MailRenderer.html(mail);
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;").contains("&amp; co");
        assertThat(MailRenderer.text(mail)).contains("Гость <script>alert(1)</script> & co");
    }
}
