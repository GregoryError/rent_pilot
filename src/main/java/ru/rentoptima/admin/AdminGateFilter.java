package ru.rentoptima.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Раздел администратора для всех, кроме роли ADMIN, не существует: на любой адрес
 * {@code /admin/**} — 404, и хозяину, и анониму (без редиректа на вход, по которому видно, что
 * раздел есть). Стоит в цепочке Spring Security перед проверкой доступа; {@code @PreAuthorize}
 * на контроллерах — второй рубеж.
 */
public class AdminGateFilter extends OncePerRequestFilter {

    public static final String PREFIX = "/admin";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isAdmin(SecurityContextHolder.getContext().getAuthentication())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean isAdmin(Authentication auth) {
        return auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
