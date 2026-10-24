package ru.rentoptima.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.auth.EmailVerificationService;
import ru.rentoptima.auth.PasswordService;
import ru.rentoptima.service.EmailService;

import java.util.List;

/**
 * Действия администратора с пользователем и его аккаунтом (тенантом). Каждое — с подтверждением в
 * интерфейсе и записью в журнал (это делает {@link AdminUsersController}); здесь — сами действия и
 * отказы. Результат — текст для администратора; {@link Outcome#ok()} = false — отказ или ошибка.
 * <p>
 * Аккаунт, в котором есть администратор, нельзя отключить (и удалить): так нельзя случайно
 * запереть самого себя.
 */
@Service
public class AdminUserActions {

    public record Outcome(boolean ok, String message, String details) {
        static Outcome ok(String message, String details) {
            return new Outcome(true, message, details);
        }

        static Outcome refused(String message) {
            return new Outcome(false, message, message);
        }
    }

    private final JdbcTemplate jdbc;
    private final PasswordService passwords;
    private final EmailVerificationService verification;
    private final EmailService email;

    public AdminUserActions(JdbcTemplate jdbc, PasswordService passwords, EmailVerificationService verification,
                            EmailService email) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.verification = verification;
        this.email = email;
    }

    /** В аккаунте есть администратор — отключать и удалять нельзя. */
    public boolean hasAdmin(long tenantId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM users WHERE tenant_id = ? AND role = 'ADMIN'",
                Integer.class, tenantId);
        return n != null && n > 0;
    }

    /**
     * Отключить аккаунт: вход запрещён всем его пользователям, их сессии завершаются. Страницы
     * бронирования работают, если не выбрано {@code publicPagesToo}.
     */
    @Transactional
    public Outcome disable(long tenantId, boolean publicPagesToo) {
        if (hasAdmin(tenantId)) return Outcome.refused("В аккаунте есть администратор — его нельзя отключить.");
        jdbc.update("UPDATE tenants SET active = FALSE, public_pages_off = ?, disabled_at = now(), updated_at = now()"
                + " WHERE id = ?", publicPagesToo, tenantId);
        jdbc.update("UPDATE users SET active = FALSE WHERE tenant_id = ?", tenantId);
        int sessions = 0;
        for (Long userId : userIds(tenantId)) sessions += passwords.expireSessions(userId, null);
        String what = publicPagesToo ? "Аккаунт и его страницы бронирования отключены." : "Аккаунт отключён.";
        return Outcome.ok(what + " Завершено сессий: " + sessions + ".",
                (publicPagesToo ? "и публичные страницы; " : "") + "сессий завершено: " + sessions);
    }

    /** Включить аккаунт: вход и страницы бронирования снова работают. */
    @Transactional
    public Outcome enable(long tenantId) {
        jdbc.update("UPDATE tenants SET active = TRUE, public_pages_off = FALSE, disabled_at = NULL, updated_at = now()"
                + " WHERE id = ?", tenantId);
        jdbc.update("UPDATE users SET active = TRUE WHERE tenant_id = ?", tenantId);
        return Outcome.ok("Аккаунт включён: вход и страницы бронирования работают.", null);
    }

    /** Письмо со ссылкой подтверждения почты ещё раз. */
    public Outcome resendVerification(AdminUsers.User u, String linkBase) {
        if (u.email() == null) return Outcome.refused("У пользователя нет адреса почты.");
        if (u.emailVerified()) return Outcome.refused("Почта уже подтверждена.");
        if (!email.smtpConfigured()) return Outcome.refused("Почта сервиса не настроена (SMTP_HOST, MAIL_FROM).");
        long wait = verification.resend(u.id(), linkBase);
        if (wait > 0) return Outcome.refused("Письмо уже отправляли только что — повторить можно через " + wait + " с.");
        return Outcome.ok("Письмо со ссылкой подтверждения отправлено.", null);
    }

    /** Ссылка на смену пароля — тем же письмом, что и «Забыли пароль?». */
    public Outcome sendResetLink(AdminUsers.User u, String linkBase) {
        if (u.email() == null) return Outcome.refused("У пользователя нет адреса почты.");
        if (!u.active()) return Outcome.refused("Пользователь отключён — сначала включите аккаунт.");
        if (!email.smtpConfigured()) return Outcome.refused("Почта сервиса не настроена (SMTP_HOST, MAIL_FROM).");
        passwords.sendResetLink(u.id(), linkBase);
        return Outcome.ok("Письмо со ссылкой на смену пароля отправлено. Ссылка действует "
                + PasswordService.RESET_TTL.toMinutes() + " минут.", null);
    }

    /** Отметить почту подтверждённой вручную (например, пользователь подтвердил её в переписке). */
    public Outcome markVerified(AdminUsers.User u) {
        if (u.email() == null) return Outcome.refused("У пользователя нет адреса почты.");
        if (u.emailVerified()) return Outcome.refused("Почта уже подтверждена.");
        verification.markVerified(u.id());
        return Outcome.ok("Почта отмечена подтверждённой.", null);
    }

    /** Адреса активных хозяев с подтверждённой почтой — для рассылки о сервисе. */
    public List<String> activeVerifiedEmails() {
        return jdbc.queryForList("SELECT DISTINCT lower(u.email) FROM users u JOIN tenants t ON t.id = u.tenant_id"
                + " WHERE u.active AND t.active AND u.email_verified AND u.email IS NOT NULL AND u.role = 'OWNER'"
                + " ORDER BY 1", String.class);
    }

    private List<Long> userIds(long tenantId) {
        return jdbc.queryForList("SELECT id FROM users WHERE tenant_id = ?", Long.class, tenantId);
    }
}
