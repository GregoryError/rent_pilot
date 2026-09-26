package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.BookingStatsService;
import ru.rentoptima.service.ExpenseService;
import ru.rentoptima.service.PricingLearningService;
import ru.rentoptima.service.SettingsService;

import java.time.LocalDate;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final PropertyRepository propertyRepo;
    private final BookingStatsService statsService;
    private final ExpenseService expenseService;
    private final SettingsService settings;

    private final PricingLearningService learningService;
    private final ru.rentoptima.repository.AiCommentRepository aiCommentRepo;
    private final ru.rentoptima.service.FeedbackAnalyticsService feedbackAnalytics;

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(required = false) String m, Model model) {
        Long tenantId = AuthContext.tenantId();
        var properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        var settingsMap = settings.getSettingsMap(tenantId);

        LocalDate now = LocalDate.now();
        java.time.YearMonth targetMonth;
        try {
            targetMonth = (m != null && !m.isBlank())
                    ? java.time.YearMonth.parse(m)
                    : java.time.YearMonth.from(now);
        } catch (Exception e) {
            targetMonth = java.time.YearMonth.from(now);
        }
        LocalDate monthStart = targetMonth.atDay(1);
        LocalDate monthEnd = targetMonth.atEndOfMonth();



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
        model.addAttribute("learningSummary", learningService.getLatestSummary(tenantId));

        var aiComments = aiCommentRepo.findByTenantIdOrderByCreatedAtDesc(
                tenantId, org.springframework.data.domain.PageRequest.of(0, 5));
        model.addAttribute("aiComments", aiComments);

        Double avgRating = null;
        if (!properties.isEmpty()) {
            try {
                avgRating = feedbackAnalytics.averageRating(tenantId, properties.get(0).getId());
            } catch (Exception e) {
                // ignore
            }
        }
        model.addAttribute("avgRating", avgRating);

        model.addAttribute("currentMonth", targetMonth.toString());
        model.addAttribute("prevMonth", targetMonth.minusMonths(1).toString());
        model.addAttribute("nextMonth", targetMonth.plusMonths(1).toString());
        model.addAttribute("monthLabel", targetMonth.getMonth()
                .getDisplayName(java.time.format.TextStyle.FULL, new java.util.Locale("ru"))
                + " " + targetMonth.getYear());

        java.math.BigDecimal otherExpenses = expenseService.getExpensesExcludingCleaning(tenantId, monthStart, monthEnd);
        java.math.BigDecimal netIncome = kpi.revenue()
                .subtract(kpi.cleaningCost())
                .subtract(otherExpenses);
        model.addAttribute("netIncome", netIncome);

        return "pages/dashboard/index";
    }
}
