package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.entity.User;
import ru.rentoptima.security.AppUserDetailsService;
import ru.rentoptima.service.RegistrationService;

@Slf4j
@Controller
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;
    private final AppUserDetailsService userDetailsService;

    @GetMapping("/register")
    public String registerPage() {
        return "pages/auth/register";
    }

    @PostMapping("/register")
    public String doRegister(@RequestParam String email,
                              @RequestParam String password,
                              @RequestParam String confirmPassword,
                              @RequestParam String tenantName,
                              @RequestParam(required = false) String agreedToPd,
                              HttpServletRequest request,
                              RedirectAttributes redirect,
                              Model model) {
        try {
            if (!password.equals(confirmPassword)) {
                throw new IllegalArgumentException("Пароли не совпадают");
            }
            if (agreedToPd == null) {
                throw new IllegalArgumentException("Необходимо принять условия обработки персональных данных");
            }

            User user = registrationService.register(
                    email, password, tenantName, true);

            // Auto-login
            UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
            request.getSession(true).setAttribute(
                    "SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

            return "redirect:/welcome";
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

    @GetMapping("/welcome")
    public String welcome(Model model) {
        model.addAttribute("activePage", "welcome");
        return "pages/auth/welcome";
    }
}
