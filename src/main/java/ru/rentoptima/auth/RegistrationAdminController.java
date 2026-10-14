package ru.rentoptima.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.service.EmailService;

import java.util.List;
import java.util.Locale;

/**
 * Администратор: режим подтверждения почты при регистрации и сводка по пользователям
 * (только числа — без списков людей).
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin/registration")
@RequiredArgsConstructor
public class RegistrationAdminController {

    /** Вариант переключателя: значение, подпись, пояснение, выбран ли. */
    public record Option(String value, String label, String hint, boolean checked) {}

    private final RegistrationMode mode;
    private final EmailService email;

    @GetMapping
    public String page(Model model) {
        RegistrationMode.Mode configured = mode.configured();
        RegistrationMode.Counts counts = mode.counts();
        int threshold = mode.threshold();
        boolean smtp = email.smtpConfigured();
        model.addAttribute("activePage", "admin-registration");
        model.addAttribute("smtp", smtp);
        model.addAttribute("counts", counts);
        model.addAttribute("threshold", threshold);
        model.addAttribute("options", List.of(
                new Option("SOFT", "Мягкий", "В кабинет сразу; подтвердить почту — в течение 7 дней, иначе кабинет "
                        + "закроется до подтверждения. Новая страница бронирования заработает после подтверждения.",
                        configured == RegistrationMode.Mode.SOFT),
                new Option("STRICT", "Строгий", "В кабинет — только после перехода по ссылке из письма.",
                        configured == RegistrationMode.Mode.STRICT),
                new Option("AUTO", "Автоматический", "Мягкий, пока настоящих пользователей меньше порога; на пороге "
                        + "сам переключается на строгий и пишет администратору в Telegram.",
                        configured == RegistrationMode.Mode.AUTO)));
        String effective;
        if (!smtp) {
            effective = "Мягкий без закрытия кабинета: почта не настроена, подтвердить адрес нечем";
        } else if (configured == RegistrationMode.Mode.AUTO) {
            effective = "Мягкий (настоящих " + counts.real() + " из " + threshold + ")";
        } else {
            effective = configured == RegistrationMode.Mode.STRICT ? "Строгий" : "Мягкий";
        }
        model.addAttribute("effective", effective);
        return "pages/admin/registration";
    }

    @PostMapping
    public String save(@RequestParam String mode, @RequestParam(required = false) Integer threshold,
                       RedirectAttributes redirect) {
        RegistrationMode.Mode chosen;
        try {
            chosen = RegistrationMode.Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", "Неизвестный режим");
            return "redirect:/admin/registration";
        }
        int n = threshold == null ? this.mode.threshold() : threshold;
        if (n < 1 || n > 1_000_000) {
            redirect.addFlashAttribute("error", "Порог — от 1 до 1 000 000");
            return "redirect:/admin/registration";
        }
        this.mode.configure(chosen, n);
        // AUTO с порогом ниже текущего числа — переключится на STRICT сразу
        RegistrationMode.Mode after = this.mode.checkThreshold();
        redirect.addFlashAttribute("success", after == chosen ? "Сохранено"
                : "Сохранено. Порог уже достигнут — режим переключён на строгий");
        return "redirect:/admin/registration";
    }
}
