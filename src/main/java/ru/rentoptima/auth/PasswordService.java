package ru.rentoptima.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.User;
import ru.rentoptima.repository.UserRepository;
import ru.rentoptima.security.TenantUserDetails;
import ru.rentoptima.service.EmailService;
import ru.rentoptima.service.PublicRateLimiter;
import ru.rentoptima.service.TelegramService;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Восстановление и смена пароля.
 * <p>
 * Запрос ссылки ничего не сообщает о существовании аккаунта: ответ один и тот же, найден
 * адрес или нет, сработал лимит или нет. После нового пароля все прочие сессии пользователя
 * завершаются, ссылки сброса гаснут, на почту уходит «Пароль изменён».
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordService {

    public static final Duration RESET_TTL = Duration.ofMinutes(60);
    static final int PER_EMAIL_PER_HOUR = 3;
    static final int PER_IP_PER_HOUR = 10;
    /** Неверный текущий пароль при смене — не больше стольких попыток в час. */
    static final int CHANGE_ATTEMPTS_PER_HOUR = 10;
    private static final long HOUR = 3_600_000L;

    public enum Via { EMAIL, TELEGRAM }

    private final UserRepository users;
    private final AuthTokenService tokens;
    private final PasswordEncoder encoder;
    private final SessionRegistry sessions;
    private final PublicRateLimiter limiter;
    private final AuthNotifier notifier;
    private final EmailService email;
    private final TelegramService telegram;

    public boolean emailAvailable() {
        return email.smtpConfigured();
    }

    /**
     * Отправляет ссылку сброса, если адрес принадлежит активному пользователю и способ
     * доступен. Ничего не возвращает: вызывающий в любом случае показывает нейтральный ответ.
     */
    public void requestReset(String address, Via via, String ip, String linkBase) {
        String normalized = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains("@") || normalized.length() > 255) return;
        // Лимиты считаются до поиска пользователя — одинаково для существующих и нет
        boolean ipOk = limiter.allow("forgot-ip:" + ip, PER_IP_PER_HOUR, HOUR);
        boolean emailOk = limiter.allow("forgot-email:" + normalized, PER_EMAIL_PER_HOUR, HOUR);
        if (!ipOk || !emailOk) {
            log.info("Сброс пароля: лимит запросов (ip {}, адрес скрыт)", ip);
            return;
        }
        Optional<User> found = users.findActiveByEmail(normalized);
        if (found.isEmpty()) return;
        User user = found.get();
        switch (via) {
            case EMAIL -> {
                if (!email.smtpConfigured()) return;
                notifier.resetLinkByEmail(user.getEmail(), link(linkBase, tokens.issue(user.getId(), AuthTokenService.Kind.RESET, RESET_TTL)));
            }
            case TELEGRAM -> {
                Long tenantId = user.getTenant().getId();
                if (!telegram.isConfigured(tenantId)) return;
                notifier.resetLinkByTelegram(tenantId, link(linkBase, tokens.issue(user.getId(), AuthTokenService.Kind.RESET, RESET_TTL)));
            }
        }
        log.info("Сброс пароля: ссылка выдана пользователю {} ({})", user.getId(), via);
    }

    /** Пользователь, которому принадлежит действующая ссылка сброса. */
    public Optional<User> resetTarget(String token) {
        return tokens.peek(token, AuthTokenService.Kind.RESET).flatMap(users::findById).filter(User::getActive);
    }

    /**
     * Новый пароль по ссылке. Переход по ссылке из письма заодно подтверждает почту.
     *
     * @param keepSessionId сессия, которую не завершать (текущая); null — завершить все
     * @return текст ошибки или null при успехе
     */
    @Transactional
    public String reset(String token, String password, String confirm, String keepSessionId, String linkBase) {
        Optional<User> target = resetTarget(token);
        if (target.isEmpty()) return INVALID_LINK;
        User user = target.get();
        String error = PasswordPolicy.check(password, confirm, emailOf(user));
        if (error != null) return error;
        if (tokens.consume(token, AuthTokenService.Kind.RESET).isEmpty()) return INVALID_LINK;
        user.setEmailVerified(true);
        apply(user, password, keepSessionId, linkBase);
        log.info("Пароль пользователя {} сброшен по ссылке", user.getId());
        return null;
    }

    public static final String INVALID_LINK = "Ссылка устарела или уже использована. Запросите новую.";

    /**
     * Смена пароля вошедшим пользователем.
     *
     * @return текст ошибки или null при успехе
     */
    @Transactional
    public String change(Long userId, String current, String password, String confirm,
                         String keepSessionId, String linkBase) {
        User user = users.findById(userId).orElseThrow();
        if (current == null || !encoder.matches(current, user.getPasswordHash())) {
            if (!limiter.allow("change-password:" + userId, CHANGE_ATTEMPTS_PER_HOUR, HOUR)) {
                return "Слишком много попыток. Попробуйте через час или восстановите пароль по почте.";
            }
            return "Текущий пароль указан неверно.";
        }
        String error = PasswordPolicy.check(password, confirm, emailOf(user));
        if (error != null) return error;
        if (encoder.matches(password, user.getPasswordHash())) return "Новый пароль совпадает с текущим.";
        apply(user, password, keepSessionId, linkBase);
        log.info("Пользователь {} сменил пароль", userId);
        return null;
    }

    private void apply(User user, String password, String keepSessionId, String linkBase) {
        user.setPasswordHash(encoder.encode(password));
        users.save(user);
        tokens.revokeAll(user.getId(), AuthTokenService.Kind.RESET);
        int ended = expireSessions(user.getId(), keepSessionId);
        if (ended > 0) log.info("Завершено сессий пользователя {}: {}", user.getId(), ended);
        if (user.getEmail() != null) notifier.passwordChanged(user.getEmail(), linkBase + "/forgot");
    }

    /** Завершает все сессии пользователя, кроме keepSessionId. Сессия гаснет при следующем запросе. */
    public int expireSessions(Long userId, String keepSessionId) {
        int count = 0;
        for (Object principal : sessions.getAllPrincipals()) {
            if (!(principal instanceof TenantUserDetails details) || !userId.equals(details.getUserId())) continue;
            for (SessionInformation info : sessions.getAllSessions(principal, false)) {
                if (info.getSessionId().equals(keepSessionId)) continue;
                info.expireNow();
                count++;
            }
        }
        return count;
    }

    private static String emailOf(User user) {
        return user.getEmail() != null ? user.getEmail() : user.getUsername();
    }

    private static String link(String base, String token) {
        return base + "/reset?token=" + token;
    }
}
