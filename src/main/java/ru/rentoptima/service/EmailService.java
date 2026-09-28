package ru.rentoptima.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.User;

/**
 * Отправка транзакционных писем.
 * Сейчас — заглушка (логирует в консоль). Позже — SMTP-реализация.
 * <p>
 * Чтобы включить настоящую отправку: создать SmtpEmailService, пометить @Primary,
 * добавить настройки SMTP в application.yml.
 */
@Slf4j
@Service
public class EmailService {

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
