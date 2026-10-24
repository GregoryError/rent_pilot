package ru.rentoptima.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.auth.ServiceLock;
import ru.rentoptima.entity.User;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.security.TenantUserDetails;

import java.util.List;

/**
 * «Сервис» (/admin/service): выключатель регистрации и входа и текст объявления. Каждое изменение —
 * в журнал. Выключили вход — открытые сессии хозяев завершаются сразу (сессии администраторов нет).
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminServiceController {

    static final int TITLE_MAX = 120;
    static final int TEXT_MAX = 2000;

    private final ServiceLock lock;
    private final AdminAudit audit;
    private final SessionRegistry sessions;

    @GetMapping("/admin/service")
    public String page(Model model) {
        ServiceLock.State s = lock.state();
        model.addAttribute("activePage", "admin-service");
        model.addAttribute("state", s);
        model.addAttribute("preview", lock.notice());
        model.addAttribute("overridden", lock.overridden());
        model.addAttribute("titleMax", TITLE_MAX);
        model.addAttribute("textMax", TEXT_MAX);
        return "pages/admin/service";
    }

    @PostMapping("/admin/service")
    public String save(@RequestParam(required = false) String registrationOpen,
                       @RequestParam(required = false) String loginOpen,
                       @RequestParam(required = false) String title,
                       @RequestParam(required = false) String text,
                       RedirectAttributes redirect) {
        String t = title == null ? "" : title.strip();
        String x = text == null ? "" : text.strip();
        if (t.isEmpty() || t.length() > TITLE_MAX || x.isEmpty() || x.length() > TEXT_MAX) {
            redirect.addFlashAttribute("error", "Заголовок — до " + TITLE_MAX + " символов, текст — до " + TEXT_MAX
                    + "; оба не пустые.");
            return "redirect:/admin/service";
        }
        boolean reg = "true".equals(registrationOpen);
        boolean login = "true".equals(loginOpen);
        List<String> changed = lock.save(reg, login, t, x, AuthContext.userId());
        String ended = "";
        if (changed.contains(ServiceLock.LOGIN) && !login) {
            int n = expireOwnerSessions();
            ended = "; сессий хозяев завершено: " + n;
        }
        if (changed.contains(ServiceLock.REGISTRATION)) {
            audit.ok(AdminAudit.Action.SERVICE_REGISTRATION, "service", null, reg ? "открыта" : "закрыта");
        }
        if (changed.contains(ServiceLock.LOGIN)) {
            audit.ok(AdminAudit.Action.SERVICE_LOGIN, "service", null, (login ? "открыт" : "закрыт") + ended);
        }
        if (changed.contains(ServiceLock.TITLE) || changed.contains(ServiceLock.TEXT)) {
            audit.ok(AdminAudit.Action.SERVICE_NOTICE, "service", null, null);
        }
        redirect.addFlashAttribute("notice", changed.isEmpty() ? "Ничего не изменилось."
                : "Сохранено" + (ended.isEmpty() ? "." : ended.replace("; с", ". С") + "."));
        return "redirect:/admin/service";
    }

    /** Завершает все сессии, кроме администраторских. */
    private int expireOwnerSessions() {
        int n = 0;
        for (Object principal : sessions.getAllPrincipals()) {
            if (principal instanceof TenantUserDetails u && u.getRole() == User.Role.ADMIN) continue;
            for (SessionInformation info : sessions.getAllSessions(principal, false)) {
                info.expireNow();
                n++;
            }
        }
        return n;
    }
}
