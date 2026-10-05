package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.ChannelDiagnosticsService;

@Controller
@RequiredArgsConstructor
public class ChannelDiagnosticsController {

    private final ChannelDiagnosticsService diagnostics;

    @GetMapping("/settings/channels/diagnostics")
    public String page(Model model) {
        model.addAttribute("activePage", "channels");
        model.addAttribute("report", diagnostics.build(AuthContext.tenantId()));
        model.addAttribute("orphanedBlocks", diagnostics.orphanedBlocks(AuthContext.tenantId()));
        return "pages/settings/channel-diagnostics";
    }

    @PostMapping("/settings/channels/diagnostics/remove-orphaned")
    public String removeOrphaned(RedirectAttributes redirect) {
        int removed = diagnostics.removeOrphanedBlocks(AuthContext.tenantId());
        redirect.addFlashAttribute("success", "Снято блокировок удалённых каналов: " + removed);
        return "redirect:/settings/channels/diagnostics";
    }

    @PostMapping("/settings/channels/diagnostics/clear")
    public String clear(RedirectAttributes redirect) {
        int removed = diagnostics.clear(AuthContext.tenantId());
        redirect.addFlashAttribute("success", removed == 0
                ? "Очищать нечего: журналы уже пусты"
                : "Диагностика очищена, удалено записей: " + removed);
        return "redirect:/settings/channels/diagnostics";
    }
}
