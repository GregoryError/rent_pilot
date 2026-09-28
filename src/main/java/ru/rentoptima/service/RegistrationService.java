package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.repository.TenantRepository;
import ru.rentoptima.repository.UserRepository;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final TenantRepository tenantRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final SettingsService settings;
    private final EmailService emailService;

    /**
     * Регистрация нового пользователя вместе с tenant.
     * Возвращает созданный User или бросает IllegalArgumentException при ошибке.
     */
    @Transactional
    public User register(String email, String password, String tenantName,
                         boolean agreedToTerms, boolean agreedToConsent) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("Некорректный email");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Пароль должен быть не менее 8 символов");
        }
        if (tenantName == null || tenantName.isBlank()) {
            throw new IllegalArgumentException("Введите название компании");
        }
        if (!agreedToTerms) {
            throw new IllegalArgumentException("Необходимо принять пользовательское соглашение и политику конфиденциальности");
        }
        if (!agreedToConsent) {
            throw new IllegalArgumentException("Необходимо дать согласие на обработку персональных данных");
        }

        String normalizedEmail = email.trim().toLowerCase();

        userRepo.findByEmail(normalizedEmail).ifPresent(existing -> {
            throw new IllegalArgumentException("Email уже зарегистрирован");
        });

        Tenant tenant = new Tenant();
        tenant.setName(tenantName.trim());
        tenant.setSlug(generateSlug(normalizedEmail));
        tenant.setActive(true);
        tenant.setUpdatedAt(LocalDateTime.now());
        tenant = tenantRepo.save(tenant);

        User user = new User();
        user.setTenant(tenant);
        user.setUsername(normalizedEmail);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(tenantName.trim());
        user.setRole(User.Role.OWNER);
        user.setActive(true);
        user.setEmailVerified(true);

        LocalDateTime now = LocalDateTime.now();
        user.setAgreedToPdAt(now); // legacy
        user.setAgreedToTermsAt(now);
        user.setAgreedToConsentAt(now);

        user = userRepo.save(user);

        seedDefaultSettings(tenant.getId());

        log.info("New user registered: {} (tenant {})", normalizedEmail, tenant.getId());
        return user;
    }

    private void seedDefaultSettings(Long tenantId) {
        settings.setValue(tenantId, "city", "");
        settings.setValue(tenantId, "timezone", "Europe/Moscow");
        settings.setValue(tenantId, "weekday_base_price", "3000");
        settings.setValue(tenantId, "weekend_base_price", "4000");
        settings.setValue(tenantId, "min_price_floor", "2000");
        settings.setValue(tenantId, "max_price_ceiling", "8000");
        settings.setValue(tenantId, "cleaning_cost", "1500");
        settings.setValue(tenantId, "platform_markup_pct", "18");
        settings.setValue(tenantId, "open_ahead_days", "75");
        settings.setValue(tenantId, "max_min_stay", "9");
        settings.setValue(tenantId, "autopilot_mode", "OFF"); // По умолчанию выключен — юзер сам включит
        settings.setValue(tenantId, "autopilot_interval_minutes", "360");
    }

    private String generateSlug(String email) {
        // tenant_slug на основе email + timestamp — уникально
        String base = email.split("@")[0].replaceAll("[^a-z0-9]", "");
        if (base.isBlank()) base = "tenant";
        return base + "-" + System.currentTimeMillis() % 100000;
    }
}
