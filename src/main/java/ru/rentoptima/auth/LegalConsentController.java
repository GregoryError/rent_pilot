package ru.rentoptima.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.rentoptima.legal.AccountConsents;
import ru.rentoptima.legal.Operator;
import ru.rentoptima.security.AuthContext;

/**
 * Окно согласий для тех, кто зарегистрировался до публикации документов (V42): те же два
 * чекбокса, что при регистрации. Без них кабинет закрыт; выйти и попросить удалить аккаунт
 * можно.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class LegalConsentController {

    private final AccountConsents consents;

    @GetMapping(LegalConsentInterceptor.PAGE)
    public String page(@RequestParam(required = false) String next, Model model) {
        Long userId = AuthContext.userId();
        if (userId == null) return "redirect:/login";
        if (!consents.required(userId)) return "redirect:" + safeNext(next);
        model.addAttribute("next", safeNext(next));
        model.addAttribute("operatorEmail", Operator.EMAIL);
        return "pages/auth/legal-consent";
    }

    @PostMapping(LegalConsentInterceptor.PAGE)
    public String accept(@RequestParam(required = false) String agreedToTerms,
                         @RequestParam(required = false) String agreedToConsent,
                         @RequestParam(required = false) String next,
                         Model model) {
        Long userId = AuthContext.userId();
        if (userId == null) return "redirect:/login";
        if (agreedToTerms == null || agreedToConsent == null) {
            model.addAttribute("error", "Отметьте оба пункта — без них кабинет недоступен");
            model.addAttribute("next", safeNext(next));
            model.addAttribute("operatorEmail", Operator.EMAIL);
            return "pages/auth/legal-consent";
        }
        consents.accept(userId);
        log.info("User {} accepted current legal documents", userId);
        return "redirect:" + safeNext(next);
    }

    /** Только путь на нашем сайте: «/…», но не «//…» и не адрес другого сайта. */
    static String safeNext(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")
                || next.startsWith(LegalConsentInterceptor.PAGE)) {
            return "/calendar/grid";
        }
        return next;
    }
}
