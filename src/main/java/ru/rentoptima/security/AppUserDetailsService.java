package ru.rentoptima.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import ru.rentoptima.auth.ServiceLock;
import ru.rentoptima.entity.User;
import ru.rentoptima.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final ServiceLock serviceLock;

    /**
     * Вход закрыт выключателем «Сервис открыт» — входит только администратор. Остальным — как
     * «нет такого пользователя»: ответ (объявление) одинаковый, есть аккаунт или нет.
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsernameActive(username)
                .filter(u -> u.getRole() == User.Role.ADMIN || serviceLock.loginOpen())
                .map(TenantUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }
}
