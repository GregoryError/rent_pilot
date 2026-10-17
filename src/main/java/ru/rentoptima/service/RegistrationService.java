package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.auth.EmailDomains;
import ru.rentoptima.auth.EmailVerificationService;
import ru.rentoptima.auth.PasswordPolicy;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.repository.TenantRepository;
import ru.rentoptima.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final TenantRepository tenantRepo;
    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final SettingsService settings;

    /**
     * Регистрация нового пользователя вместе с tenant.
     * Возвращает созданный User или бросает IllegalArgumentException при ошибке.
     */
    @Transactional
    public User register(String email, String password, String tenantName,
                         boolean agreedToTerms, boolean agreedToConsent, boolean strict) {
        if (email == null || email.isBlank() || !EMAIL.matcher(email.trim()).matches() || email.trim().length() > 255) {
            throw new IllegalArgumentException("Некорректный email");
        }
        String passwordError = PasswordPolicy.check(password, null, email);
        if (passwordError != null) {
            throw new IllegalArgumentException(passwordError);
        }
        String domainError = EmailDomains.check(email);
        if (domainError != null) {
            throw new IllegalArgumentException(domainError);
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

        // без учёта регистра и по логину тоже: вход ищет пользователя по логину
        if (userRepo.countByLoginOrEmailIgnoreCase(normalizedEmail) > 0) {
            throw new IllegalArgumentException("Этот адрес уже зарегистрирован. Если это вы — войдите "
                    + "или восстановите пароль.");
        }

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
        // адрес ещё не подтверждён: плашка и срок (STRICT — кабинет сразу закрыт до подтверждения),
        // страница бронирования ждёт подтверждения
        EmailVerificationService.prepareNew(user, strict);

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
        // Цена — у квартиры (мастер). Эти четыре ключа заводятся пустыми, чтобы их можно было
        // заполнить в настройках: цены по умолчанию — только подсказка для новых квартир,
        // пустые пределы — 50–250% цены квартиры (PricingEngine.bounds).
        settings.setValue(tenantId, "weekday_base_price", "");
        settings.setValue(tenantId, "weekend_base_price", "");
        settings.setValue(tenantId, "min_price_floor", "");
        settings.setValue(tenantId, "max_price_ceiling", "");
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
