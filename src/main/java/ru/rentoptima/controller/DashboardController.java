package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.BookingStatsService;
import ru.rentoptima.service.ExpenseService;
import ru.rentoptima.service.SettingsService;

import java.time.LocalDate;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final PropertyRepository propertyRepo;
    private final BookingStatsService statsService;
    private final ExpenseService expenseService;
    private final SettingsService settings;

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        Long tenantId = AuthContext.tenantId();
        var properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        var settingsMap = settings.getSettingsMap(tenantId);

        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);
        LocalDate monthEnd = now.plusMonths(1).withDayOfMonth(1).minusDays(1);

        // Current month KPIs
        var kpi = statsService.getKpi(tenantId, monthStart, monthEnd);
        // Previous month for comparison
        var prevKpi = statsService.getKpi(tenantId, monthStart.minusMonths(1), monthStart.minusDays(1));
        // Booking pace
        var pace = statsService.getBookingPace(tenantId);
        // Action items (from gpt_proposal: "what to do today")
        var actionItems = statsService.getActionItems(tenantId);

        model.addAttribute("activePage", "dashboard");
        model.addAttribute("properties", properties);
        model.addAttribute("settings", settingsMap);
        model.addAttribute("autopilotMode", settingsMap.getOrDefault("autopilot_mode", "OFF"));
        model.addAttribute("kpi", kpi);
        model.addAttribute("prevKpi", prevKpi);
        model.addAttribute("pace", pace);
        model.addAttribute("actionItems", actionItems);

        return "pages/dashboard/index";
    }
}
