package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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
        return "pages/settings/channel-diagnostics";
    }
}
