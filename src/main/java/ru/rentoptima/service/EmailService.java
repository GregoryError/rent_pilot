package ru.rentoptima.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import ru.rentoptima.mail.Mail;
import ru.rentoptima.mail.MailRenderer;

import java.nio.charset.StandardCharsets;

/**
 * Отправка писем через SMTP (Unisender Go). Включается переменными SMTP_HOST и MAIL_FROM;
 * пока их нет, письма пропускаются.
 * <p>
 * У каждого письма: HTML и текстовая часть в UTF-8, Reply-To на MAIL_REPLY_TO (на адрес
 * отправителя no-reply письма не принимаются) и заголовок {@value #UNISENDER_HEADER}
 * с выключенным отслеживанием переходов и прочтений. Без него Unisender подменяет
 * ссылки на свой адрес-переадресацию, и одноразовые ссылки из писем может «потратить»
 * предпросмотр почтового сервиса.
 */
@Slf4j
@Service
public class EmailService {

    public static final String UNISENDER_HEADER = "X-UNISENDER-GO";

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String smtpHost;
    private final String from;
    private final String replyTo;
    private final boolean skipUnsubscribe;

    public EmailService(ObjectProvider<JavaMailSender> mailSender,
                        @Value("${spring.mail.host:}") String smtpHost,
                        @Value("${app.mail.from:}") String from,
                        @Value("${app.mail.reply-to:}") String replyTo,
                        @Value("${app.mail.skip-unsubscribe:false}") boolean skipUnsubscribe) {
        this.mailSender = mailSender;
        this.smtpHost = smtpHost == null ? "" : smtpHost.trim();
        this.from = from == null ? "" : from.trim();
        this.replyTo = replyTo == null ? "" : replyTo.trim();
        this.skipUnsubscribe = skipUnsubscribe;
    }

    /** SMTP настроен: заданы SMTP_HOST и MAIL_FROM. Без них письма молча пропускаются. */
    public boolean smtpConfigured() {
        return !smtpHost.isEmpty() && !from.isEmpty();
    }

    /**
     * Значение заголовка {@value #UNISENDER_HEADER}. {@code skip_unsubscribe} выключает
     * ссылку отписки, которую Unisender иначе добавляет в каждое письмо; работает, только
     * если его разрешила поддержка (MAIL_SKIP_UNSUBSCRIBE=1 ставить после этого).
     */
    public String unisenderHeader() {
        return "{\"track_links\":0,\"track_read\":0" + (skipUnsubscribe ? ",\"skip_unsubscribe\":1" : "") + "}";
    }

    /**
     * Отправляет письмо. Никогда не бросает исключений: сбой почты не должен ломать то,
     * ради чего письмо отправлялось.
     *
     * @return true, если письмо передано SMTP-серверу
     */
    public boolean send(Mail mail) {
        if (!smtpConfigured() || mail.to() == null || mail.to().isBlank()) return false;
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) return false;
        try {
            MimeMessage message = sender.createMimeMessage();
            message.setFrom(address(from));
            message.setRecipient(MimeMessage.RecipientType.TO, new InternetAddress(mail.to().trim(), true));
            if (!replyTo.isEmpty()) message.setReplyTo(new InternetAddress[]{address(replyTo)});
            message.setSubject(mail.subject(), StandardCharsets.UTF_8.name());
            message.setHeader(UNISENDER_HEADER, unisenderHeader());

            MimeBodyPart text = new MimeBodyPart();
            text.setText(MailRenderer.text(mail), StandardCharsets.UTF_8.name(), "plain");
            MimeBodyPart html = new MimeBodyPart();
            html.setText(MailRenderer.html(mail), StandardCharsets.UTF_8.name(), "html");
            MimeMultipart body = new MimeMultipart("alternative");
            body.addBodyPart(text);
            body.addBodyPart(html);
            message.setContent(body);

            sender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("Письмо не отправлено ({}): {}", mail.subject(), e.getMessage());
            return false;
        }
    }

    /** «OptiRent <no-reply@optirent.ru>» — имя может быть и по-русски, кодируется в UTF-8. */
    private static InternetAddress address(String value) throws Exception {
        InternetAddress parsed = new InternetAddress(value, false);
        if (parsed.getPersonal() != null) {
            return new InternetAddress(parsed.getAddress(), parsed.getPersonal(), StandardCharsets.UTF_8.name());
        }
        return parsed;
    }
}
