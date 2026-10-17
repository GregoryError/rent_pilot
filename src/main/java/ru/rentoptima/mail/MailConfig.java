package ru.rentoptima.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/**
 * SMTP-клиент. Шифрование задаёт SMTP_SECURITY: {@code starttls} (порт 587 и 25) или
 * {@code ssl} (порт 465, TLS с первого байта). Без переменной выбирается по порту —
 * смена порта в .env не требует правки yml. Незашифрованного режима нет: пароль SMTP
 * не должен идти открытым текстом.
 * <p>
 * Бин заменяет автоконфигурацию Spring Boot (она отступает при своём JavaMailSender).
 */
@Slf4j
@Configuration
public class MailConfig {

    public enum Security { STARTTLS, SSL }

    @Bean
    public JavaMailSender javaMailSender(@Value("${spring.mail.host:}") String host,
                                         @Value("${spring.mail.port:587}") int port,
                                         @Value("${spring.mail.username:}") String username,
                                         @Value("${spring.mail.password:}") String password,
                                         @Value("${app.mail.security:}") String security) {
        Security resolved = resolve(security, port);
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host == null ? "" : host.trim());
        sender.setPort(port);
        if (username != null && !username.isBlank()) sender.setUsername(username.trim());
        if (password != null && !password.isBlank()) sender.setPassword(password);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        sender.setJavaMailProperties(properties(resolved, username != null && !username.isBlank()));
        if (!sender.getHost().isEmpty()) log.info("SMTP: {}:{}, {}", sender.getHost(), port, resolved);
        return sender;
    }

    /** Режим из SMTP_SECURITY; пусто или непонятное значение — по порту: 465 — ssl, иначе starttls. */
    public static Security resolve(String security, int port) {
        String s = security == null ? "" : security.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "starttls", "tls" -> {
                return Security.STARTTLS;
            }
            case "ssl", "smtps" -> {
                return Security.SSL;
            }
            default -> {
                if (!s.isEmpty()) log.warn("SMTP_SECURITY={} не распознан, режим выбран по порту {}", security, port);
                return port == 465 ? Security.SSL : Security.STARTTLS;
            }
        }
    }

    public static Properties properties(Security security, boolean auth) {
        Properties p = new Properties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", String.valueOf(auth));
        if (security == Security.SSL) {
            p.put("mail.smtp.ssl.enable", "true");
        } else {
            // required: если сервер не предложит STARTTLS, письмо не уйдёт открытым текстом
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        p.put("mail.smtp.ssl.checkserveridentity", "true");
        p.put("mail.smtp.connectiontimeout", "5000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        return p;
    }
}
