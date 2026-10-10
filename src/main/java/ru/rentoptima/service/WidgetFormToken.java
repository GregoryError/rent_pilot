package ru.rentoptima.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Проверка времени заполнения формы бронирования — защита от ботов без капчи.
 * <p>
 * Сервер выдаёт подписанную отметку времени вместе с расчётом стоимости (гость
 * получает его, когда выбрал даты), а при отправке формы проверяет, что с тех пор
 * прошло не меньше {@link #MIN_FILL_MILLIS}: человек не успевает ввести имя и телефон
 * быстрее, скрипт отправляет форму сразу. Время берётся с сервера, а не от клиента,
 * поэтому подделать его нельзя.
 * <p>
 * Ключ подписи выводится из ENCRYPTION_KEY, чтобы токены переживали перезапуск
 * приложения; без него (локальный запуск) ключ случайный на время жизни процесса.
 */
@Component
public class WidgetFormToken {

    static final long MIN_FILL_MILLIS = 3_000;
    static final long MAX_AGE_MILLIS = 12 * 3_600_000L;

    public enum Check { OK, TOO_FAST, EXPIRED, INVALID }

    private final byte[] key;

    public WidgetFormToken(@Value("${encryption.key:}") String secret) {
        if (secret == null || secret.isBlank()) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
        } else {
            key = hmac(("widget-form-token:" + secret).getBytes(StandardCharsets.UTF_8),
                    "optirent".getBytes(StandardCharsets.UTF_8));
        }
    }

    public String issue(Long widgetId) {
        return issue(widgetId, System.currentTimeMillis());
    }

    String issue(Long widgetId, long now) {
        return now + "." + sign(widgetId, now);
    }

    public Check check(Long widgetId, String token) {
        return check(widgetId, token, System.currentTimeMillis());
    }

    Check check(Long widgetId, String token, long now) {
        if (token == null) return Check.INVALID;
        int dot = token.indexOf('.');
        if (dot <= 0) return Check.INVALID;
        long issuedAt;
        try {
            issuedAt = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            return Check.INVALID;
        }
        byte[] given = token.substring(dot + 1).getBytes(StandardCharsets.UTF_8);
        byte[] expected = sign(widgetId, issuedAt).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, expected)) return Check.INVALID;
        long age = now - issuedAt;
        if (age < MIN_FILL_MILLIS) return Check.TOO_FAST;
        if (age > MAX_AGE_MILLIS) return Check.EXPIRED;
        return Check.OK;
    }

    private String sign(Long widgetId, long issuedAt) {
        byte[] mac = hmac(key, (widgetId + ":" + issuedAt).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC недоступен", e);
        }
    }
}
