package ru.rentoptima.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.rentoptima.entity.User;
import ru.rentoptima.security.TenantUserDetails;

import java.io.IOException;

/**
 * Вход закрыт — сессия хозяина гаснет на первом же запросе: контекст сбрасывается, сессия
 * завершается, дальше запрос идёт как анонимный (кабинет уводит на вход с объявлением, публичные
 * страницы работают как обычно). Дополняет завершение сессий через реестр при выключении —
 * ловит и те, что в реестр не попали. Администратора не трогает.
 */
public class ServiceLockFilter extends OncePerRequestFilter {

    private final ServiceLock lock;

    public ServiceLockFilter(ServiceLock lock) {
        this.lock = lock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TenantUserDetails u && u.getRole() != User.Role.ADMIN
                && !lock.loginOpen()) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
        }
        chain.doFilter(request, response);
    }
}
