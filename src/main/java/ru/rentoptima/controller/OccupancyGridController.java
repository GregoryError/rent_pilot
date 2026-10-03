package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.OccupancyGridService;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Шахматка. Модель сетки собирает {@link OccupancyGridService}; здесь только
 * разбор параметров периода и элементы управления страницы.
 */
@Controller
@RequestMapping("/calendar/grid")
@RequiredArgsConstructor
public class OccupancyGridController {

    private static final int DEFAULT_DAYS = 160;
    private static final int MIN_DAYS = 1;
    /** Не продуктовый лимит, а защита от from=…&days=100000: год с запасом. */
    private static final int MAX_DAYS = 366;
    private static final int[] SPAN_PRESETS = {7, 14, 30, 60, 90, 160, 365};

    private final OccupancyGridService gridService;

    @GetMapping
    public String grid(@RequestParam(required = false) String from,
                       @RequestParam(required = false) Integer days,
                       @RequestParam(required = false) String to,
                       Model model) {
        Long tenantId = AuthContext.tenantId();

        LocalDate today = LocalDate.now();
        LocalDate start = parseDate(from, today);
        // «to» (включительно) приходит из формы произвольного периода и важнее days.
        LocalDate toInclusive = parseDate(to, null);
        int requested = toInclusive != null
                ? (int) Math.min(MAX_DAYS, ChronoUnit.DAYS.between(start, toInclusive) + 1)
                : (days == null ? DEFAULT_DAYS : days);
        int span = clamp(requested, MIN_DAYS, MAX_DAYS);
        LocalDate lastDay = start.plusDays(span - 1);

        model.addAttribute("activePage", "grid");
        model.addAttribute("grid", gridService.build(tenantId, start, span));
        model.addAttribute("startIso", start.toString());
        model.addAttribute("endIso", lastDay.toString());
        model.addAttribute("span", span);
        model.addAttribute("spanOptions", spanOptions(span));
        model.addAttribute("prevStart", start.minusDays(span));
        model.addAttribute("nextStart", start.plusDays(span));
        model.addAttribute("rangeLabel", russianRange(start, lastDay));
        return "pages/calendar/grid";
    }

    /** Пресеты горизонта + текущее значение, если оно в пресеты не попало. */
    private static List<SpanOption> spanOptions(int span) {
        List<SpanOption> options = new ArrayList<>();
        boolean matched = false;
        for (int preset : SPAN_PRESETS) {
            if (!matched && span < preset) {
                options.add(new SpanOption(span, span + " " + pluralDays(span), true));
                matched = true;
            }
            if (preset == span) matched = true;
            options.add(new SpanOption(preset, preset + " " + pluralDays(preset), preset == span));
        }
        if (!matched) options.add(new SpanOption(span, span + " " + pluralDays(span), true));
        return options;
    }

    private static String pluralDays(int n) {
        int mod100 = n % 100;
        int mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return "дней";
        if (mod10 == 1) return "день";
        if (mod10 >= 2 && mod10 <= 4) return "дня";
        return "дней";
    }

    private static String russianRange(LocalDate from, LocalDate to) {
        String left = from.getDayOfMonth() + " " + OccupancyGridService.russianMonthGen(from.getMonthValue())
                + (from.getYear() == to.getYear() ? "" : " " + from.getYear());
        String right = to.getDayOfMonth() + " " + OccupancyGridService.russianMonthGen(to.getMonthValue()) + " " + to.getYear();
        return left + " — " + right;
    }

    private static LocalDate parseDate(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public record SpanOption(int days, String label, boolean selected) {}
}
