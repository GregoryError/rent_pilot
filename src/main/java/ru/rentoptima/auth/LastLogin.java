package ru.rentoptima.auth;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import ru.rentoptima.security.TenantUserDetails;

/**
 * {@code users.last_login_at} (V47): успешный вход формой и автоматический вход после
 * регистрации. Показывается только в админке.
 */
@Component
public class LastLogin {

    private final JdbcTemplate jdbc;

    public LastLogin(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    public void onLogin(InteractiveAuthenticationSuccessEvent event) {
        if (event.getAuthentication().getPrincipal() instanceof TenantUserDetails u) record(u.getUserId());
    }

    public void record(Long userId) {
        if (userId != null) jdbc.update("UPDATE users SET last_login_at = now() WHERE id = ?", userId);
    }
}
