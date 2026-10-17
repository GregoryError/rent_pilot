package ru.rentoptima.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuthContext {

    private AuthContext() {
    }

    public static TenantUserDetails current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TenantUserDetails tud) {
            return tud;
        }
        throw new IllegalStateException("No authenticated user");
    }


    public static Long tenantId() {
        return current().getTenantId();
    }

    /** Тенант вошедшего пользователя или null для анонима — для публичных страниц. */
    public static Long tenantIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TenantUserDetails details) {
            return details.getTenantId();
        }
        return null;
    }

    public static Long userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TenantUserDetails details) {
            return details.getUserId();
        }
        return null;
    }
}
