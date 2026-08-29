package ru.rentoptima.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AuthContext {

    private AuthContext() {}

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

    public static Long userId() {
        return current().getUserId();
    }
}
