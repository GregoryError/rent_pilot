package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.RealtyCalendarClient;
import ru.rentoptima.service.SettingsService;

@Slf4j
@Controller
@RequestMapping("/settings/integrations")
@RequiredArgsConstructor
public class IntegrationsController {

    private final SettingsService settings;
    private final RealtyCalendarClient rcClient;

    @GetMapping
    public String page(Model model) {
        Long tenantId = AuthContext.tenantId();

        String username = settings.getValue(tenantId, "rc_username");
        boolean hasPassword = settings.getEncryptedValue(tenantId, "rc_password") != null;

        model.addAttribute("activePage", "settings");
        model.addAttribute("rcUsername", username != null ? username : "");
        model.addAttribute("hasPassword", hasPassword);
        model.addAttribute("configured", username != null && !username.isBlank() && hasPassword);
        return "pages/settings/integrations";
    }

    @PostMapping("/rc")
    public String saveRc(@RequestParam String rcUsername,
                         @RequestParam(required = false) String rcPassword,
                         RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();

        settings.setValue(tenantId, "rc_username", rcUsername.trim());

        if (rcPassword != null && !rcPassword.isBlank()) {
            settings.setEncryptedValue(tenantId, "rc_password", rcPassword);
        }

        // Invalidate cached token so next call re-authenticates with new creds
        rcClient.invalidateToken(tenantId);

        redirect.addFlashAttribute("success", "Настройки RC сохранены");
        return "redirect:/settings/integrations";
    }

    @PostMapping("/rc/test")
    @ResponseBody
    public java.util.Map<String, Object> testRc() {
        Long tenantId = AuthContext.tenantId();
        try {
            boolean ok = rcClient.testConnection(tenantId);
            if (ok) {
                return java.util.Map.of("ok", true, "message", "Подключение работает");
            } else {
                return java.util.Map.of("ok", false, "message", "Не удалось получить токен");
            }
        } catch (Exception e) {
            log.warn("RC test failed for tenant {}: {}", tenantId, e.getMessage());
            return java.util.Map.of("ok", false, "message", e.getMessage());
        }
    }

    @PostMapping("/rc/clear")
    public String clearRc(RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        settings.setValue(tenantId, "rc_username", "");
        settings.setEncryptedValue(tenantId, "rc_password", null);
        rcClient.invalidateToken(tenantId);
        redirect.addFlashAttribute("success", "Данные RC удалены");
        return "redirect:/settings/integrations";
    }
}
