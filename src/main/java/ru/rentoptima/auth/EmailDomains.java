package ru.rentoptima.auth;

import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Одноразовые почтовые домены: на такой адрес не придёт ссылка восстановления через месяц,
 * поэтому регистрацию и смену адреса с ним не принимаем. Список — файл
 * {@code auth/disposable-domains.txt}, по домену в строке; дополнять там. Поддомены
 * запрещённого домена тоже запрещены.
 */
public final class EmailDomains {

    static final String FILE = "auth/disposable-domains.txt";
    public static final String DISPOSABLE = "Адреса одноразовой почты не принимаем. Укажите постоянный адрес: "
            + "на него придёт ссылка, если понадобится восстановить доступ.";

    private static final Set<String> BLOCKED = load();

    private EmailDomains() {
    }

    /** Текст отказа или null, если домен подходит. */
    public static String check(String email) {
        return disposable(email) ? DISPOSABLE : null;
    }

    public static boolean disposable(String email) {
        if (email == null) return false;
        int at = email.lastIndexOf('@');
        if (at < 0) return false;
        String domain = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        while (!domain.isEmpty()) {
            if (BLOCKED.contains(domain)) return true;
            int dot = domain.indexOf('.');
            if (dot < 0) break;
            domain = domain.substring(dot + 1);
        }
        return false;
    }

    static int size() {
        return BLOCKED.size();
    }

    private static Set<String> load() {
        Set<String> set = new HashSet<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource(FILE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String d = line.trim().toLowerCase(Locale.ROOT);
                if (!d.isEmpty() && !d.startsWith("#")) set.add(d);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Нет списка одноразовых доменов " + FILE, e);
        }
        return Set.copyOf(set);
    }
}
