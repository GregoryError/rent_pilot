package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.entity.User;
import ru.rentoptima.onboarding.OnboardingService;
import ru.rentoptima.security.AppUserDetailsService;
import ru.rentoptima.service.RegistrationService;

@Slf4j
@Controller
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;
    private final AppUserDetailsService userDetailsService;
    private final OnboardingService onboarding;
    private final SessionRegistry sessionRegistry;

    @GetMapping("/register")
    public String registerPage() {
        return "pages/auth/register";
    }

    @PostMapping("/register")
    public String doRegister(@RequestParam String email,
                             @RequestParam String password,
                             @RequestParam String confirmPassword,
                             @RequestParam String tenantName,
                             @RequestParam(required = false) String agreedToTerms,
                             @RequestParam(required = false) String agreedToConsent,
                             HttpServletRequest request,
                             RedirectAttributes redirect,
                             Model model) {
        try {
            if (!password.equals(confirmPassword)) {
                throw new IllegalArgumentException("Пароли не совпадают");
            }

            User user = registrationService.register(
                    email, password, tenantName,
                    agreedToTerms != null,
                    agreedToConsent != null);

            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
            jakarta.servlet.http.HttpSession session = request.getSession(true);
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());
            // в реестр — чтобы смена пароля могла завершить и эту сессию
            sessionRegistry.registerNewSession(session.getId(), userDetails);

            // Новичка встречает мастер первой настройки
            onboarding.begin(user.getId(), user.getTenant().getId());
            return "redirect:/onboarding";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("email", email);
            model.addAttribute("tenantName", tenantName);
            return "pages/auth/register";
        } catch (Exception e) {
            log.error("Registration failed", e);
            model.addAttribute("error", "Ошибка регистрации. Попробуйте позже.");
            return "pages/auth/register";
        }
    }

    /** Прежняя страница приветствия: её заменил мастер первой настройки. Адрес оставлен редиректом. */
    @GetMapping("/welcome")
    public String welcome() {
        return "redirect:/calendar/grid";
    }
}
