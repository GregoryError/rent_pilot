package ru.rentoptima.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.User;
import ru.rentoptima.repository.UserRepository;
import ru.rentoptima.service.EmailService;
import ru.rentoptima.service.PublicRateLimiter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Мягкое подтверждение почты.
 * <p>
 * Неподтверждённый хозяин работает как обычно, видит плашку, а страница бронирования
 * аккаунта, созданного после V38, гостей не принимает. После {@code verify_deadline} кабинет
 * закрывается экраном «Подтвердите почту, чтобы продолжить» ({@link EmailVerificationInterceptor}).
 * <p>
 * Без SMTP подтвердить адрес нечем, поэтому ничего из этого не действует: ни плашки, ни
 * закрытой страницы, ни экрана.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    public static final Duration TOKEN_TTL = Duration.ofDays(7);
    public static final Duration GRACE = Duration.ofDays(7);
    public static final Duration RESEND_PAUSE = Duration.ofMinutes(2);
    static final int EMAIL_CHANGES_PER_DAY = 3;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    /** Состояние пользователя для плашки, экрана и страницы бронирования. */
    public record Status(Long userId, String email, boolean verified, OffsetDateTime deadline,
                         boolean pageGated, OffsetDateTime lastSentAt) {

        /** Нужно подтвердить: адрес есть, не подтверждён. */
        public boolean pending() {
            return email != null && !verified;
        }

        /** Срок вышел — кабинет закрыт до подтверждения. */
        public boolean locked(OffsetDateTime now) {
            return pending() && deadline != null && !now.isBefore(deadline);
        }

        /** Сколько секунд ждать до повторной отправки письма; 0 — можно сейчас. */
        public long resendWaitSeconds(OffsetDateTime now) {
            if (lastSentAt == null) return 0;
            long left = Duration.between(now, lastSentAt.plus(RESEND_PAUSE)).toSeconds();
            return Math.max(0, left);
        }
    }

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AuthTokenService tokens;
    private final AuthNotifier notifier;
    private final EmailService email;
    private final PasswordEncoder encoder;
    private final PublicRateLimiter limiter;

    /** Подтверждение действует, только когда есть чем отправить письмо. */
    public boolean active() {
        return email.smtpConfigured();
    }

    public Optional<Status> status(Long userId) {
        if (userId == null || !active()) return Optional.empty();
        List<Status> found = jdbc.query("SELECT u.id, u.email, u.email_verified, u.verify_deadline,"
                        + " u.page_needs_verified_email,"
                        + " (SELECT max(t.created_at) FROM auth_tokens t WHERE t.user_id = u.id AND t.kind = 'VERIFY') AS last_sent"
                        + " FROM users u WHERE u.id = ?",
                (rs, i) -> new Status(rs.getLong("id"), rs.getString("email"), rs.getBoolean("email_verified"),
                        rs.getObject("verify_deadline", OffsetDateTime.class), rs.getBoolean("page_needs_verified_email"),
                        rs.getObject("last_sent", OffsetDateTime.class)),
                userId);
        return found.stream().findFirst();
    }

    /**
     * Страница бронирования тенанта ещё не принимает гостей: её хозяин зарегистрировался после
     * V38 и не подтвердил почту.
     */
    public boolean pageInactive(Long tenantId) {
        if (tenantId == null || !active()) return false;
        Integer n = jdbc.queryForObject("SELECT count(*) FROM users WHERE tenant_id = ? AND active"
                + " AND page_needs_verified_email AND NOT email_verified AND email IS NOT NULL", Integer.class, tenantId);
        return n != null && n > 0;
    }

    /**
     * Поля нового пользователя: адрес не подтверждён, срок {@link #GRACE}, страница бронирования
     * ждёт подтверждения. Вызывается до сохранения.
     */
    public static void prepareNew(User user) {
        user.setEmailVerified(false);
        user.setVerifyDeadline(OffsetDateTime.now().plus(GRACE));
        user.setPageNeedsVerifiedEmail(true);
    }

    /** Первое письмо после регистрации. */
    public void sendFirst(User user, String linkBase) {
        if (active()) sendLinkNow(user.getId(), user.getEmail(), linkBase);
    }

    /**
     * Повторная отправка — не чаще раза в {@link #RESEND_PAUSE}.
     *
     * @return сколько секунд ещё ждать; 0 — письмо отправлено (или подтверждать нечего)
     */
    public long resend(Long userId, String linkBase) {
        Optional<Status> s = status(userId);
        if (s.isEmpty() || !s.get().pending()) return 0;
        long wait = s.get().resendWaitSeconds(OffsetDateTime.now());
        if (wait > 0) return wait;
        sendLinkNow(userId, s.get().email(), linkBase);
        return 0;
    }

    private void sendLinkNow(Long userId, String address, String linkBase) {
        String token = tokens.issue(userId, AuthTokenService.Kind.VERIFY, TOKEN_TTL);
        notifier.verifyLink(address, linkBase + "/verify?token=" + token);
        log.info("Подтверждение почты: письмо пользователю {}", userId);
    }

    /** Переход по ссылке из письма. @return пользователь, чей адрес подтверждён */
    @Transactional
    public Optional<Long> confirm(String token) {
        Optional<Long> userId = tokens.consume(token, AuthTokenService.Kind.VERIFY);
        userId.ifPresent(this::markVerified);
        return userId;
    }

    /**
     * Адрес подтверждён по ссылке. Пишет в базу напрямую — не вызывать в транзакции, где
     * сущность этого пользователя потом сохраняется (затрёт); там — {@link #markVerified(User)}.
     */
    public void markVerified(Long userId) {
        jdbc.update("UPDATE users SET email_verified = TRUE, email_verified_at = coalesce(email_verified_at, now())"
                + " WHERE id = ?", userId);
        tokens.revokeAll(userId, AuthTokenService.Kind.VERIFY);
    }

    /** То же для сущности, которую вызывающий сохранит сам (сброс пароля по ссылке). */
    public void markVerified(User user) {
        user.setEmailVerified(true);
        if (user.getEmailVerifiedAt() == null) user.setEmailVerifiedAt(OffsetDateTime.now());
        tokens.revokeAll(user.getId(), AuthTokenService.Kind.VERIFY);
    }

    /**
     * Смена адреса до подтверждения: по текущему паролю, не больше {@value #EMAIL_CHANGES_PER_DAY}
     * раз в сутки. Логин меняется вместе с адресом, если совпадал с ним.
     *
     * @return текст ошибки или null — адрес сменён, письмо отправлено
     */
    @Transactional
    public String changeEmail(Long userId, String password, String newEmail, String linkBase) {
        User user = users.findById(userId).orElseThrow();
        if (Boolean.TRUE.equals(user.getEmailVerified())) return "Адрес уже подтверждён.";
        if (password == null || !encoder.matches(password, user.getPasswordHash())) return "Пароль указан неверно.";
        String address = newEmail == null ? "" : newEmail.trim().toLowerCase(Locale.ROOT);
        if (address.length() > 255 || !EMAIL.matcher(address).matches()) return "Проверьте адрес почты.";
        if (address.equals(user.getEmail())) return "Это и есть текущий адрес.";
        Integer taken = jdbc.queryForObject("SELECT count(*) FROM users WHERE id <> ? AND"
                + " (lower(email) = ? OR lower(username) = ?)", Integer.class, userId, address, address);
        if (taken != null && taken > 0) return "Этот адрес уже занят другим аккаунтом.";
        if (!limiter.allow("email-change:" + userId, EMAIL_CHANGES_PER_DAY, Duration.ofDays(1).toMillis())) {
            return "Адрес можно менять не больше " + EMAIL_CHANGES_PER_DAY + " раз в сутки. Попробуйте завтра.";
        }
        boolean loginIsEmail = user.getUsername() != null && user.getUsername().equalsIgnoreCase(user.getEmail());
        user.setEmail(address);
        if (loginIsEmail) user.setUsername(address);
        user.setEmailVerified(false);
        users.save(user);
        if (active()) sendLinkNow(userId, address, linkBase);
        log.info("Пользователь {} сменил адрес почты до подтверждения", userId);
        return null;
    }
}
