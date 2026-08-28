package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.SettingsService;

import java.time.LocalDate;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final PropertyRepository propertyRepo;
    private final BookingRepository bookingRepo;
    private final SettingsService settings;

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        Long tenantId = AuthContext.tenantId();
        var properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        var settingsMap = settings.getSettingsMap(tenantId);

        model.addAttribute("activePage", "dashboard");
        model.addAttribute("properties", properties);
        model.addAttribute("settings", settingsMap);
        model.addAttribute("autopilotMode", settingsMap.getOrDefault("autopilot_mode", "OFF"));

        return "pages/dashboard/index";
    }
}
