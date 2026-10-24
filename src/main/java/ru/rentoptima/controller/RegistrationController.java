package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.rentoptima.auth.EmailVerificationService;
import ru.rentoptima.auth.PasswordPolicy;
import ru.rentoptima.auth.LastLogin;
import ru.rentoptima.auth.RegistrationMode;
import ru.rentoptima.auth.SignupSource;
import ru.rentoptima.entity.User;
import ru.rentoptima.onboarding.OnboardingService;
import ru.rentoptima.security.AppUserDetailsService;
import ru.rentoptima.seo.SiteSettings;
import ru.rentoptima.service.PublicRateLimiter;
import ru.rentoptima.service.RegistrationService;
import ru.rentoptima.service.WidgetFormToken;

@Slf4j
@Controller
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;
    private final AppUserDetailsService userDetailsService;
    private final OnboardingService onboarding;
    private final SessionRegistry sessionRegistry;
    private final EmailVerificationService verification;
    private final SiteSettings site;
    private final RegistrationMode mode;
    private final WidgetFormToken formTokens;
    private final PublicRateLimiter rateLimiter;
    private final LastLogin lastLogin;
    private final ru.rentoptima.auth.ServiceLock serviceLock;

    /** Регистраций с одного IP в час. */
    static final int PER_IP_PER_HOUR = 5;
    /** Область токена времени заполнения формы ({@link WidgetFormToken}). */
    static final String FORM_SCOPE = "register";

    @GetMapping("/register")
    public String registerPage(@RequestParam(name = "utm_source", required = false) String utmSource,
                               @RequestParam(name = "utm_medium", required = false) String utmMedium,
                               @RequestParam(name = "utm_campaign", required = false) String utmCampaign,
                               @RequestParam(required = false) String ref,
                               HttpServletRequest request, Model model) {
        // Источник — в скрытые поля формы: без cookie и сессии до регистрации
        model.addAttribute("source", SignupSource.of(utmSource, utmMedium, utmCampaign, ref,
                request.getHeader("Referer"), request.getServerName()));
        return form(model, null, null, null);
    }

    @PostMapping("/register")
    public String doRegister(@RequestParam(required = false) String email,
                             @RequestParam(required = false) String password,
                             @RequestParam(required = false) String confirmPassword,
                             @RequestParam(required = false) String tenantName,
                             @RequestParam(required = false) String agreedToTerms,
                             @RequestParam(required = false) String agreedToConsent,
                             @RequestParam(name = HONEYPOT, required = false) String honeypot,
                             @RequestParam(required = false) String formToken,
                             @RequestParam(name = "utm_source", required = false) String utmSource,
                             @RequestParam(name = "utm_medium", required = false) String utmMedium,
                             @RequestParam(name = "utm_campaign", required = false) String utmCampaign,
                             @RequestParam(required = false) String ref,
                             HttpServletRequest request,
                             Model model) {
        String ip = request.getRemoteAddr();
        // источник из скрытых полей — и в регистрацию, и обратно в форму, если она вернётся с ошибкой
        SignupSource source = SignupSource.of(utmSource, utmMedium, utmCampaign, ref, null, request.getServerName());
        model.addAttribute("source", source);
        // Регистрация закрыта выключателем «Сервис открыт» — не только в интерфейсе
        if (!serviceLock.registrationOpen()) {
            log.info("Регистрация отклонена: регистрация закрыта (ip {})", ip);
            return form(model, null, email, tenantName);
        }
        // Скрытое поле заполняют только боты; ответ — общий, без подсказки, что не так
        if (honeypot != null && !honeypot.isBlank()) {
            log.info("Регистрация отклонена: заполнено скрытое поле (ip {})", ip);
            return form(model, "Не получилось зарегистрироваться. Обновите страницу и попробуйте ещё раз.", email, tenantName);
        }
        switch (formTokens.check(FORM_SCOPE, formToken)) {
            case TOO_FAST -> {
                log.info("Регистрация отклонена: форма заполнена слишком быстро (ip {})", ip);
                return form(model, "Форма отправлена слишком быстро. Проверьте поля и отправьте ещё раз.", email, tenantName);
            }
            case EXPIRED -> {
                return form(model, "Страница была открыта слишком долго. Отправьте форму ещё раз.", email, tenantName);
            }
            case INVALID -> {
                log.info("Регистрация отклонена: нет токена формы (ip {})", ip);
                return form(model, "Не получилось зарегистрироваться. Обновите страницу и попробуйте ещё раз.", email, tenantName);
            }
            case OK -> { }
        }
        try {
            if (password == null || !password.equals(confirmPassword)) {
                throw new IllegalArgumentException("Пароли не совпадают");
            }
            if (!rateLimiter.allow("register:" + ip, PER_IP_PER_HOUR, 3_600_000L)) {
                log.info("Регистрация отклонена: лимит с IP {}", ip);
                throw new IllegalArgumentException("Слишком много регистраций с вашего адреса. Попробуйте через час.");
            }

            boolean strict = mode.forNewUser() == RegistrationMode.Mode.STRICT;
            User user = registrationService.register(
                    email, password, tenantName,
                    agreedToTerms != null,
                    agreedToConsent != null,
                    strict,
                    source);
            lastLogin.record(user.getId());

            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
            // новая сессия под вошедшего — старый идентификатор (до входа) больше не действует
            if (request.getSession(false) != null) request.changeSessionId();
            jakarta.servlet.http.HttpSession session = request.getSession(true);
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());
            // в реестр — чтобы смена пароля могла завершить и эту сессию
            sessionRegistry.registerNewSession(session.getId(), userDetails);

            verification.sendFirst(user, site.linkBase(request));

            // Новичка встречает мастер первой настройки; в STRICT — сначала «Проверьте почту»
            onboarding.begin(user.getId(), user.getTenant().getId());
            return strict ? "redirect:/verify-email" : "redirect:/onboarding";
        } catch (IllegalArgumentException e) {
            return form(model, e.getMessage(), email, tenantName);
        } catch (DataIntegrityViolationException e) {
            // тот же адрес регистрируют одновременно — уникальный индекс не пустил второго
            return form(model, "Этот адрес уже зарегистрирован. Если это вы — войдите или восстановите пароль.",
                    email, tenantName);
        } catch (Exception e) {
            log.error("Registration failed", e);
            return form(model, "Ошибка регистрации. Попробуйте позже.", email, tenantName);
        }
    }

    /** Имя скрытого поля-ловушки: людям его не видно, боты заполняют. */
    public static final String HONEYPOT = "company_site";

    private String form(Model model, String error, String email, String tenantName) {
        if (error != null) model.addAttribute("error", error);
        if (!model.containsAttribute("source")) model.addAttribute("source", SignupSource.NONE);
        boolean closed = !serviceLock.registrationOpen();
        model.addAttribute("registrationClosed", closed);
        if (closed) model.addAttribute("serviceNotice", serviceLock.notice());
        model.addAttribute("email", email);
        model.addAttribute("tenantName", tenantName);
        model.addAttribute("formToken", formTokens.issue(FORM_SCOPE));
        model.addAttribute("honeypot", HONEYPOT);
        model.addAttribute("passwordRules", PasswordPolicy.RULES);
        model.addAttribute("minLength", PasswordPolicy.MIN_LENGTH);
        return "pages/auth/register";
    }

    /** Прежняя страница приветствия: её заменил мастер первой настройки. Адрес оставлен редиректом. */
    @GetMapping("/welcome")
    public String welcome() {
        return "redirect:/calendar/grid";
    }
}
