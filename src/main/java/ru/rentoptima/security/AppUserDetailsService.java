package ru.rentoptima.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.User;
import ru.rentoptima.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.password}")
    private String adminPassword;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsernameActive(username)
                .map(TenantUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    // test stage admin password
    @PostConstruct
    public void addAdmin() {
        User admin = userRepository.findByUsernameActive("admin")
                .orElseThrow(() -> new UsernameNotFoundException("User admin not found"));

        admin.setPasswordHash(
                passwordEncoder.encode(adminPassword)
        );

        userRepository.save(admin);
    }

}
