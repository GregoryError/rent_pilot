package ru.rentoptima.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;

/**
 * Публичные адреса удалённых аккаунтов ({@code deleted_public_links}, V49): по ним отвечаем 410.
 * Хранится SHA-256 от «вид:код», сам код — нет.
 */
@Component
public class GoneLinks {

    /** Вид адреса: часть хеша, чтобы одинаковые коды разных видов не путались. */
    public enum Kind { PAGE, SECRET, QR, FEEDBACK, HOUSEKEEPER, ICAL }

    private final JdbcTemplate jdbc;

    public GoneLinks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isGone(Kind kind, String code) {
        if (code == null || code.isBlank() || code.length() > 200) return false;
        Integer n = jdbc.queryForObject("SELECT count(*) FROM deleted_public_links WHERE code_hash = ?",
                Integer.class, hash(kind, code));
        return n != null && n > 0;
    }

    /** Запомнить адреса — в той же транзакции, что и удаление аккаунта. */
    public void remember(Kind kind, Collection<String> codes) {
        for (String code : codes) {
            if (code == null || code.isBlank()) continue;
            jdbc.update("INSERT INTO deleted_public_links(code_hash, kind) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    hash(kind, code), kind.name());
        }
    }

    static String hash(Kind kind, String code) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((kind.name() + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
