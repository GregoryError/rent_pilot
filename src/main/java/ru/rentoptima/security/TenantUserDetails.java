package ru.rentoptima.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import ru.rentoptima.entity.User;
import java.util.Collection;
import java.util.List;

@Getter
public class TenantUserDetails implements UserDetails {

    private final Long userId;
    private final Long tenantId;
    private final String username;
    private final String password;
    private final String displayName;
    private final User.Role role;
    private final boolean active;

    public TenantUserDetails(User user) {
        this.userId = user.getId();
        this.tenantId = user.getTenant().getId();
        this.username = user.getUsername();
        this.password = user.getPasswordHash();
        this.displayName = user.getDisplayName();
        this.role = user.getRole();
        this.active = user.getActive();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public boolean isEnabled() {
        return active;
    }

    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
}
