package ru.rentoptima.auth;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.entity.User;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.seo.SiteSettings;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;

/**
 * /forgot — запрос ссылки, /reset — новый пароль по ссылке, /account/password — смена
 * пароля в кабинете. GET по ссылке только показывает форму: токен гасится при отправке,
 * иначе ссылку «потратил» бы предпросмотр почты или мессенджера.
 */
@Controller
@RequiredArgsConstructor
public class PasswordController {

    private final PasswordService passwords;
    private final SiteSettings site;
    private final ServiceLock serviceLock;

    /** Вход закрыт выключателем «Сервис открыт» — вместо формы объявление; null — открыт. */
    private String closed(String heading, Model model) {
        if (serviceLock.loginOpen()) return null;
        model.addAttribute("heading", heading);
        model.addAttribute("serviceNotice", serviceLock.notice());
        return "pages/auth/service-closed";
    }

    @GetMapping("/forgot")
    public String forgot(@RequestParam(required = false) String sent, Model model) {
        String closed = closed("Восстановление пароля", model);
        if (closed != null) return closed;
        model.addAttribute("emailAvailable", passwords.emailAvailable());
        model.addAttribute("sent", "email".equals(sent) || "telegram".equals(sent) ? sent : null);
        return "pages/auth/forgot";
    }

    @PostMapping("/forgot")
    public String requestLink(@RequestParam(required = false) String email,
                              @RequestParam(defaultValue = "email") String via,
                              HttpServletRequest request, Model model) {
        String closed = closed("Восстановление пароля", model);
        if (closed != null) return closed;
        PasswordService.Via channel = "telegram".equals(via) ? PasswordService.Via.TELEGRAM : PasswordService.Via.EMAIL;
        passwords.requestReset(email, channel, request.getRemoteAddr(), site.linkBase(request));
        return "redirect:/forgot?sent=" + (channel == PasswordService.Via.TELEGRAM ? "telegram" : "email");
    }

    @GetMapping("/reset")
    public String resetForm(@RequestParam(required = false) String token, Model model, HttpServletResponse response) {
        noStore(response);
        String closed = closed("Новый пароль", model);
        if (closed != null) return closed;
        Optional<User> user = passwords.resetTarget(token);
        model.addAttribute("valid", user.isPresent());
        model.addAttribute("token", token);
        model.addAttribute("rules", PasswordPolicy.RULES);
        model.addAttribute("minLength", PasswordPolicy.MIN_LENGTH);
        return "pages/auth/reset";
    }

    @PostMapping("/reset")
    public String reset(@RequestParam(required = false) String token,
                        @RequestParam(required = false) String password,
                        @RequestParam(required = false) String confirmPassword,
                        HttpServletRequest request, HttpServletResponse response, Model model) {
        noStore(response);
        String closed = closed("Новый пароль", model);
        if (closed != null) return closed;
        String error = passwords.reset(token, password, confirmPassword, request.getRequestedSessionId(),
                site.linkBase(request));
        if (error == null) return "redirect:/login?reset";
        model.addAttribute("valid", !PasswordService.INVALID_LINK.equals(error));
        model.addAttribute("token", token);
        model.addAttribute("error", error);
        model.addAttribute("rules", PasswordPolicy.RULES);
        model.addAttribute("minLength", PasswordPolicy.MIN_LENGTH);
        return "pages/auth/reset";
    }

    @GetMapping("/account/password")
    public String changeForm(Model model) {
        model.addAttribute("activePage", "account-password");
        model.addAttribute("rules", PasswordPolicy.RULES);
        model.addAttribute("minLength", PasswordPolicy.MIN_LENGTH);
        return "pages/account/password";
    }

    @PostMapping("/account/password")
    public String change(@RequestParam(required = false) String currentPassword,
                         @RequestParam(required = false) String password,
                         @RequestParam(required = false) String confirmPassword,
                         HttpServletRequest request, Model model, RedirectAttributes redirect) {
        String error = passwords.change(AuthContext.userId(), currentPassword, password, confirmPassword,
                request.getSession().getId(), site.linkBase(request));
        if (error == null) {
            redirect.addFlashAttribute("changed", true);
            return "redirect:/account/password";
        }
        // не «error»: его layout показывает всплывающим сообщением, текст был бы дважды
        model.addAttribute("pwError", error);
        return changeForm(model);
    }

    /** Страницы с токеном в адресе не кэшируются и не передают адрес дальше по ссылкам. */
    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
