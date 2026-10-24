package ru.rentoptima.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.seo.SiteSettings;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * /verify — ссылка из письма, /verify-email — экран, когда срок без подтверждения вышел,
 * /account/verify/resend и /account/email — повторное письмо и смена адреса (из плашки и
 * с экрана).
 */
@Controller
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationService verification;
    private final SiteSettings site;
    private final ServiceLock serviceLock;

    @GetMapping("/verify")
    public String confirm(@RequestParam(required = false) String token, Model model,
                          HttpServletResponse response, RedirectAttributes redirect) {
        response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
        response.setHeader("Referrer-Policy", "no-referrer");
        // Вход закрыт выключателем «Сервис открыт» — ссылка не тратится, показываем объявление
        if (!serviceLock.loginOpen()) {
            model.addAttribute("heading", "Подтверждение почты");
            model.addAttribute("serviceNotice", serviceLock.notice());
            return "pages/auth/service-closed";
        }
        Optional<Long> userId = verification.confirm(token);
        Long current = AuthContext.userId();
        if (userId.isPresent()) {
            if (current == null) return "redirect:/login?verified";
            redirect.addFlashAttribute("success", "Почта подтверждена");
            return "redirect:/calendar/grid";
        }
        // Ссылку уже открывали (например, почтовый сервис проверил её раньше человека)
        if (current != null && verification.status(current).map(s -> !s.pending()).orElse(true)) {
            redirect.addFlashAttribute("success", "Почта уже подтверждена");
            return "redirect:/calendar/grid";
        }
        model.addAttribute("loggedIn", current != null);
        return "pages/auth/verify-invalid";
    }

    @GetMapping("/verify-email")
    public String lockedScreen(Model model) {
        Optional<EmailVerificationService.Status> s = verification.status(AuthContext.userId());
        if (s.isEmpty() || !s.get().pending()) return "redirect:/calendar/grid";
        OffsetDateTime now = OffsetDateTime.now();
        model.addAttribute("email", s.get().email());
        model.addAttribute("locked", s.get().locked(now));
        // регистрация в режиме STRICT: кабинет закрыт с самого начала — «Проверьте почту»
        model.addAttribute("strict", s.get().strict());
        model.addAttribute("neverSent", s.get().lastSentAt() == null);
        model.addAttribute("resendWait", s.get().resendWaitSeconds(now));
        return "pages/auth/verify-email";
    }

    @PostMapping("/account/verify/resend")
    public String resend(@RequestParam(required = false) String back, HttpServletRequest request,
                         RedirectAttributes redirect) {
        long wait = verification.resend(AuthContext.userId(), site.linkBase(request));
        if (wait > 0) {
            redirect.addFlashAttribute("error", "Письмо уже отправлено. Повторить можно через " + wait + " сек.");
        } else {
            redirect.addFlashAttribute("success", "Письмо отправлено. Проверьте почту и папку «Спам».");
        }
        return "redirect:" + safeBack(back);
    }

    @PostMapping("/account/email")
    public String changeEmail(@RequestParam(required = false) String email,
                              @RequestParam(required = false) String password,
                              @RequestParam(required = false) String back,
                              HttpServletRequest request, RedirectAttributes redirect) {
        String error = verification.changeEmail(AuthContext.userId(), password, email, site.linkBase(request));
        if (error != null) {
            redirect.addFlashAttribute("error", error);
        } else {
            redirect.addFlashAttribute("success", "Адрес изменён, письмо отправлено. Входите теперь с новым адресом.");
        }
        return "redirect:" + safeBack(back);
    }

    /** Возврат на страницу, откуда пришли, — только внутри сайта. */
    static String safeBack(String back) {
        if (back == null || !back.startsWith("/") || back.startsWith("//") || back.contains("\\")
                || back.contains("\r") || back.contains("\n")) {
            return "/calendar/grid";
        }
        return back;
    }
}
