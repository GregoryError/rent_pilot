package ru.rentoptima.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Одноразовые токены для ссылок из писем и Telegram: 32 байта из SecureRandom, в адресе —
 * base64url, в базе ({@code auth_tokens}) — только SHA-256. Срок и отметка использования
 * проверяются одним UPDATE, поэтому одну ссылку нельзя потратить дважды даже параллельно.
 */
@Service
public class AuthTokenService {

    public enum Kind { RESET, VERIFY }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public AuthTokenService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Новый токен. Прежние неиспользованные токены того же вида у пользователя гасятся:
     * действует только ссылка из последнего письма.
     *
     * @return токен для адреса — в базе его нет, второй раз его не получить
     */
    @Transactional
    public String issue(Long userId, Kind kind, Duration ttl) {
        revokeAll(userId, kind);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO auth_tokens(user_id, kind, token_hash, expires_at)"
                        + " VALUES (?, ?, ?, now() + make_interval(secs => ?))",
                userId, kind.name(), hash(token), ttl.toSeconds());
        return token;
    }

    /** Пользователь, если токен действует. Не гасит его: так открывается форма по ссылке. */
    public Optional<Long> peek(String token, Kind kind) {
        if (!wellFormed(token)) return Optional.empty();
        List<Long> ids = jdbc.queryForList("SELECT user_id FROM auth_tokens WHERE token_hash = ? AND kind = ?"
                + " AND used_at IS NULL AND expires_at > now()", Long.class, hash(token), kind.name());
        return ids.stream().findFirst();
    }

    /** Гасит токен и возвращает пользователя; пусто — ссылка чужая, просрочена или уже использована. */
    public Optional<Long> consume(String token, Kind kind) {
        if (!wellFormed(token)) return Optional.empty();
        List<Long> ids = jdbc.queryForList("UPDATE auth_tokens SET used_at = now() WHERE token_hash = ? AND kind = ?"
                + " AND used_at IS NULL AND expires_at > now() RETURNING user_id", Long.class, hash(token), kind.name());
        return ids.stream().findFirst();
    }

    /** Гасит все действующие токены вида — например, после смены пароля. */
    public void revokeAll(Long userId, Kind kind) {
        jdbc.update("UPDATE auth_tokens SET used_at = now() WHERE user_id = ? AND kind = ? AND used_at IS NULL",
                userId, kind.name());
    }

    /** Когда пользователю последний раз выдавался токен вида — для паузы между повторными письмами. */
    public Optional<java.time.OffsetDateTime> lastIssuedAt(Long userId, Kind kind) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT max(created_at) FROM auth_tokens WHERE user_id = ? AND kind = ?",
                java.time.OffsetDateTime.class, userId, kind.name()));
    }

    static boolean wellFormed(String token) {
        return token != null && token.length() == 43 && token.matches("[A-Za-z0-9_-]+");
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
