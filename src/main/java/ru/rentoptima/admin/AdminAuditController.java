package ru.rentoptima.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Журнал действий администратора: последние записи, фильтр по действию и по пользователю. */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAuditController {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm",
            Locale.forLanguageTag("ru"));

    private final AdminAudit audit;

    /** Строка таблицы: всё уже в виде текста и ссылок, цвет результата — здесь. */
    public record Row(String when, String admin, String action, String target, String targetLink, String result,
                      String resultColor, String details) {
    }

    @GetMapping("/admin/audit")
    public String page(@RequestParam(required = false) String action,
                       @RequestParam(required = false) Long user,
                       Model model) {
        AdminAudit.Action a = null;
        try {
            if (action != null && !action.isBlank()) a = AdminAudit.Action.valueOf(action);
        } catch (IllegalArgumentException ignored) {
            // незнакомое действие — без фильтра
        }
        List<Row> rows = audit.recent(a, user == null ? null : "user", user, 300).stream().map(e -> new Row(
                e.at().format(WHEN), e.admin() == null ? "—" : e.admin(), e.label(),
                e.targetType() == null ? "" : e.targetId() == null ? e.targetType() : e.targetType() + " #" + e.targetId(),
                "user".equals(e.targetType()) && e.targetId() != null ? "/admin/users/" + e.targetId() : null,
                switch (e.result()) {
                    case OK -> "выполнено";
                    case FAILED -> "ошибка";
                    case REFUSED -> "отказано";
                },
                switch (e.result()) {
                    case OK -> "var(--green)";
                    case FAILED -> "var(--red)";
                    case REFUSED -> "var(--amber)";
                },
                e.details() == null ? "" : e.details())).toList();
        model.addAttribute("activePage", "admin-audit");
        model.addAttribute("rows", rows);
        model.addAttribute("actions", AdminAudit.actionLabels());
        model.addAttribute("selectedAction", a == null ? "" : a.name());
        model.addAttribute("userFilter", user);
        return "pages/admin/audit";
    }
}
