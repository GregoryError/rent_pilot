package ru.rentoptima.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.User;

import java.nio.charset.StandardCharsets;

/**
 * Отправка писем.
 * <p>
 * {@link #send} работает через SMTP и включается переменными окружения SMTP_HOST,
 * SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD, MAIL_FROM; пока они не заданы, письма
 * пропускаются. Письма регистрации ниже — по-прежнему заглушки (пишут в лог).
 */
@Slf4j
@Service
public class EmailService {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String smtpHost;
    private final String from;

    public EmailService(ObjectProvider<JavaMailSender> mailSender,
                        @Value("${spring.mail.host:}") String smtpHost,
                        @Value("${app.mail.from:}") String from) {
        this.mailSender = mailSender;
        this.smtpHost = smtpHost == null ? "" : smtpHost.trim();
        this.from = from == null ? "" : from.trim();
    }

    /** SMTP настроен: заданы SMTP_HOST и MAIL_FROM. Без них письма молча пропускаются. */
    public boolean smtpConfigured() {
        return !smtpHost.isEmpty() && !from.isEmpty();
    }

    /**
     * Отправляет текстовое письмо. Никогда не бросает исключений: письмо — дополнение
     * к тому, что человек и так видит на экране, его сбой ничего не должен ломать.
     *
     * @return true, если письмо передано SMTP-серверу
     */
    public boolean send(String to, String subject, String text) {
        if (!smtpConfigured() || to == null || to.isBlank()) return false;
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) return false;
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to.trim());
            helper.setSubject(subject);
            helper.setText(text, false);
            sender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("Письмо не отправлено ({}): {}", subject, e.getMessage());
            return false;
        }
    }

    /**
     * Отправить письмо подтверждения email.
     * Пока — только лог. Ссылка формируется по шаблону /verify-email?token=...
     */
    public void sendVerificationEmail(User user, String token, String baseUrl) {
        String link = baseUrl + "/verify-email?token=" + token;
        log.info("[EMAIL STUB] Verification email for {}: link={}", user.getEmail(), link);
        // TODO: реальная отправка через SMTP
    }

    /**
     * Отправить письмо восстановления пароля.
     */
    public void sendPasswordResetEmail(User user, String token, String baseUrl) {
        String link = baseUrl + "/reset-password?token=" + token;
        log.info("[EMAIL STUB] Password reset for {}: link={}", user.getEmail(), link);
        // TODO: реальная отправка через SMTP
    }

    /** Проверка что email-отправка включена (для UI). */
    public boolean isEnabled() {
        return false;
    }
}
