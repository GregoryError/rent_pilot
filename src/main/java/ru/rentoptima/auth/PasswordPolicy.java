package ru.rentoptima.auth;

import java.util.Locale;

/**
 * Требования к новому паролю — те же, что показаны под полем заранее
 * ({@link #RULES}): от {@value #MIN_LENGTH} символов и не совпадает с адресом почты.
 * Пароли, заданные раньше, не проверяются.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;
    public static final String RULES = "Не короче " + MIN_LENGTH + " символов и не совпадает с адресом почты.";

    private PasswordPolicy() {
    }

    /** Текст ошибки или null, если пароль подходит. */
    public static String check(String password, String confirm, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "Пароль короче " + MIN_LENGTH + " символов.";
        }
        if (email != null && password.trim().toLowerCase(Locale.ROOT).equals(email.trim().toLowerCase(Locale.ROOT))) {
            return "Пароль не должен совпадать с адресом почты.";
        }
        if (confirm != null && !password.equals(confirm)) {
            return "Пароли не совпадают.";
        }
        return null;
    }
}
