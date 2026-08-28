package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.SettingsService;

import java.util.Map;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping
    public String settings(Model model) {
        Long tenantId = AuthContext.tenantId();
        model.addAttribute("settings", settingsService.getAllForTenant(tenantId));
        return "pages/settings/index";
    }

    @PostMapping
    public String updateSettings(@RequestParam Map<String, String> params, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        // Filter out Spring internal params
        params.entrySet().removeIf(e -> e.getKey().startsWith("_"));
        settingsService.updateSettings(tenantId, params);
        redirect.addFlashAttribute("success", "Настройки сохранены");
        return "redirect:/settings";
    }
}
