package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.BookingStatsService;
import ru.rentoptima.service.DashboardForecastService;
import ru.rentoptima.service.DashboardForecastService.DayRevenue;
import ru.rentoptima.service.DashboardForecastService.MonthForecast;
import ru.rentoptima.service.DashboardForecastService.Optimization;
import ru.rentoptima.service.OccupancyGridService;
import ru.rentoptima.service.ExpenseService;
import ru.rentoptima.service.PricingLearningService;
import ru.rentoptima.service.SettingsService;
import ru.rentoptima.repository.ManualOverrideRepository;
import java.time.LocalDateTime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private final ManualOverrideRepository overrideRepo;
    private final OccupancyGridService gridService;
    private final DashboardForecastService forecastService;
    private final ChannelRepository channelRepo;

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

        // Блок 4.6: hero-метрики, шахматка месяца, график выручки, статусы каналов
        MonthForecast forecast = forecastService.forecast(tenantId, targetMonth, now);
        model.addAttribute("grid", gridService.build(tenantId, monthStart, targetMonth.lengthOfMonth()));
        model.addAttribute("hero", hero(forecast));
        model.addAttribute("revenueBars", revenueBars(forecast.daily(), now));
        model.addAttribute("revenueMax", money(maxDaily(forecast.daily())));
        model.addAttribute("hasRevenue", maxDaily(forecast.daily()).signum() > 0);
        model.addAttribute("optimization", optimizationView(forecast.optimization()));
        model.addAttribute("channelChips", channelChips(tenantId));

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
        var activeOverrides = overrideRepo.findAllActive(tenantId, LocalDateTime.now());
        model.addAttribute("activeOverrides", activeOverrides);

        return "pages/dashboard/index";
    }

    private static Hero hero(MonthForecast f) {
        boolean hasEstimate = f.estimated().signum() > 0;
        String revenueNote = hasEstimate
                ? "по броням " + money(f.actual()) + " + оценка " + money(f.estimated())
                : "по суммам броней";
        String occupancyNote = f.busyNights() + " из " + f.capacityNights() + " ночей";
        String potentialNote = f.freeFutureNights() > 0
                ? "свободно ночей: " + f.freeFutureNights() + ", по вашим ценам"
                : "свободных ночей не осталось";
        return new Hero(money(f.total()), hasEstimate, revenueNote,
                trimZero(f.occupancyPct()) + "%", occupancyNote,
                String.valueOf(f.stays()),
                money(f.freeFutureValue()), potentialNote);
    }

    private static List<RevenueBar> revenueBars(List<DayRevenue> daily, LocalDate today) {
        BigDecimal max = maxDaily(daily);
        List<RevenueBar> bars = new ArrayList<>(daily.size());
        for (DayRevenue d : daily) {
            int day = d.date().getDayOfMonth();
            StringBuilder cls = new StringBuilder("rev-chart__col");
            if (!d.past())               cls.append(" is-future");
            if (d.date().equals(today))  cls.append(" is-today");

            String label = day + " " + OccupancyGridService.russianMonthGen(d.date().getMonthValue());
            String readout;
            if (d.total().signum() == 0) {
                readout = label + ": нет выручки";
            } else if (d.estimated().signum() == 0) {
                readout = label + ": " + money(d.actual()) + " по броням";
            } else if (d.actual().signum() == 0) {
                readout = label + ": " + money(d.estimated()) + " — оценка по плановой цене";
            } else {
                readout = label + ": " + money(d.total()) + " (по броням " + money(d.actual())
                        + ", оценка " + money(d.estimated()) + ")";
            }
            if (!d.past() && d.total().signum() > 0) readout += ", ещё не наступило";

            bars.add(new RevenueBar(
                    cls.toString(),
                    "height: " + pct(d.actual(), max) + "%",
                    "height: " + pct(d.estimated(), max) + "%",
                    d.actual().signum() > 0, d.estimated().signum() > 0,
                    (day == 1 || day % 5 == 0) ? String.valueOf(day) : "",
                    readout));
        }
        return bars;
    }

    private static BigDecimal maxDaily(List<DayRevenue> daily) {
        BigDecimal max = BigDecimal.ZERO;
        for (DayRevenue d : daily) max = max.max(d.total());
        return max;
    }

    private static String pct(BigDecimal value, BigDecimal max) {
        if (max.signum() == 0) return "0";
        double p = value.doubleValue() * 100.0 / max.doubleValue();
        return String.format(Locale.ROOT, "%.1f", p);
    }

    private static OptimizationView optimizationView(Optimization o) {
        if (o == null) return null;
        BigDecimal max = o.userTotal().max(o.recommendedTotal());
        int upliftSign = o.uplift().signum();
        String upliftText = (upliftSign > 0 ? "+" : upliftSign < 0 ? "−" : "") + money(o.uplift().abs());
        String verdict = upliftSign > 0
                ? "Рекомендованные цены выше ваших — на свободных ночах можно заработать больше."
                : upliftSign < 0
                    ? "Ваши цены выше рекомендованных — свободные ночи могут продаваться медленнее."
                    : "Ваши цены совпадают с рекомендованными.";
        return new OptimizationView(o.nights(),
                money(o.userTotal()), "width: " + pct(o.userTotal(), max) + "%",
                money(o.recommendedTotal()), "width: " + pct(o.recommendedTotal(), max) + "%",
                upliftText,
                upliftSign > 0 ? "tag tag--green" : upliftSign < 0 ? "tag tag--amber" : "tag tag--muted",
                verdict);
    }

    private List<ChannelChip> channelChips(Long tenantId) {
        List<ChannelChip> chips = new ArrayList<>();
        LocalDateTime nowTime = LocalDateTime.now();
        for (Channel ch : channelRepo.findByTenantIdAndActiveTrue(tenantId)) {
            if (ch.getChannelType() == Channel.ChannelType.MANUAL
                    || ch.getChannelType() == Channel.ChannelType.WIDGET) continue;
            String state;
            String status;
            if (!Boolean.TRUE.equals(ch.getSyncEnabled())) {
                state = "idle";
                status = "синхронизация выключена";
            } else if (ch.getLastError() != null && !ch.getLastError().isBlank()) {
                state = "error";
                status = "ошибка синхронизации";
            } else if (ch.getLastSyncAt() == null) {
                state = "idle";
                status = "ещё не синхронизирован";
            } else {
                state = "ok";
                status = "обновлён " + ago(ch.getLastSyncAt(), nowTime);
            }
            chips.add(new ChannelChip(ch.getName(), "channel-chip channel-chip--" + state, status));
        }
        return chips;
    }

    private static String ago(LocalDateTime then, LocalDateTime now) {
        long minutes = Math.max(0, Duration.between(then, now).toMinutes());
        if (minutes < 1) return "только что";
        if (minutes < 60) return minutes + " мин назад";
        long hours = minutes / 60;
        if (hours < 48) return hours + " ч назад";
        return (hours / 24) + " дн назад";
    }

    /** 12 300 ₽ — с неразрывными пробелами, без копеек. */
    private static String money(BigDecimal value) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('\u00A0');
        DecimalFormat format = new DecimalFormat("#,##0", symbols);
        return format.format(value.setScale(0, RoundingMode.HALF_UP)) + "\u00A0₽";
    }

    private static String trimZero(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    public record Hero(String revenue, boolean revenueEstimated, String revenueNote,
                       String occupancy, String occupancyNote,
                       String stays,
                       String potential, String potentialNote) {}

    public record RevenueBar(String cssClasses, String actualStyle, String estimatedStyle,
                             boolean hasActual, boolean hasEstimated,
                             String axisLabel, String readout) {}

    public record OptimizationView(int nights,
                                   String userTotal, String userStyle,
                                   String recommendedTotal, String recommendedStyle,
                                   String uplift, String upliftCss, String verdict) {}

    public record ChannelChip(String name, String cssClasses, String status) {}
}
