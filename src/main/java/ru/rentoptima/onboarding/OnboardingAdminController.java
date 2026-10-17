package ru.rentoptima.onboarding;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Воронка онбординга для администратора системы. Только агрегаты по дням: сколько раз
 * случилось каждое событие. Данных конкретных пользователей и тенантов здесь нет — их нет и
 * в таблице, из которой страница читает.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class OnboardingAdminController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");

    /** Группы событий и их подписи — в порядке воронки. */
    private static final Map<String, Map<String, String>> GROUPS = new LinkedHashMap<>();

    static {
        Map<String, String> wizard = new LinkedHashMap<>();
        wizard.put("wizard_start", "Мастер открыт (регистрация)");
        wizard.put("branch_A", "Ветка: площадки");
        wizard.put("branch_B", "Ветка: прямые брони");
        wizard.put("branch_AB", "Ветка: и то и другое");
        wizard.put("step_property", "Объект добавлен");
        wizard.put("step_platforms", "Площадки выбраны");
        wizard.put("step_link", "Ссылка с площадки сохранена");
        wizard.put("step_page", "Страница бронирования создана");
        wizard.put("wizard_done", "Мастер пройден");
        wizard.put("wizard_skip", "Мастер пропущен");
        GROUPS.put("Мастер", wizard);
        Map<String, String> tour = new LinkedHashMap<>();
        tour.put("tour_frame_1", "Кадр 1 — шахматка");
        tour.put("tour_frame_2", "Кадр 2 — площадки");
        tour.put("tour_frame_3", "Кадр 3 — страница брони");
        tour.put("tour_frame_4", "Кадр 4 — помощь");
        tour.put("tour_done", "Тур пройден");
        tour.put("tour_close", "Тур закрыт раньше");
        GROUPS.put("Тур", tour);
        Map<String, String> steps = new LinkedHashMap<>();
        steps.put("check_property", "Добавить объект");
        steps.put("check_platform", "Подключить первую площадку");
        steps.put("check_fetched", "Площадка забрала ссылку");
        steps.put("check_booking", "Увидеть первую бронь");
        steps.put("check_page", "Создать страницу бронирования");
        steps.put("check_card", "Распечатать карточку");
        steps.put("check_all", "Все шаги пройдены");
        GROUPS.put("Чек-лист", steps);
    }

    /** События, которые показываются столбцами в таблице по дням. */
    private static final List<String> DAILY = List.of("wizard_start", "wizard_done", "wizard_skip", "tour_done",
            "check_platform", "check_all");

    private final OnboardingStats stats;

    @GetMapping("/admin/onboarding")
    public String funnel(@RequestParam(required = false, defaultValue = "30") int days, Model model) {
        int period = days == 7 || days == 90 ? days : 30;
        LocalDate to = LocalDate.now(), from = to.minusDays(period - 1);
        Map<String, Long> totals = stats.totals(from, to);
        long started = totals.getOrDefault("wizard_start", 0L);

        List<Group> groups = new ArrayList<>();
        for (var g : GROUPS.entrySet()) {
            List<Row> rows = new ArrayList<>();
            for (var e : g.getValue().entrySet()) {
                long count = totals.getOrDefault(e.getKey(), 0L);
                int percent = started == 0 ? 0 : (int) Math.min(100, Math.round(count * 100.0 / started));
                rows.add(new Row(e.getValue(), count, started == 0 ? "—" : percent + " %", "width:" + percent + "%"));
            }
            groups.add(new Group(g.getKey(), rows));
        }
        List<DayRow> dayRows = new ArrayList<>();
        stats.byDay(from, to).forEach((day, events) -> dayRows.add(new DayRow(day.format(DAY),
                DAILY.stream().map(e -> events.getOrDefault(e, 0L)).toList())));

        model.addAttribute("activePage", "admin-onboarding");
        model.addAttribute("days", period);
        model.addAttribute("periods", List.of(new Period(7, "7 дней", period == 7 ? "btn btn-primary" : "btn btn-ghost"),
                new Period(30, "30 дней", period == 30 ? "btn btn-primary" : "btn btn-ghost"),
                new Period(90, "90 дней", period == 90 ? "btn btn-primary" : "btn btn-ghost")));
        model.addAttribute("groups", groups);
        model.addAttribute("started", started);
        model.addAttribute("dailyHeaders", List.of("Открыли мастер", "Прошли", "Пропустили", "Прошли тур",
                "Подключили площадку", "Все шаги"));
        model.addAttribute("dayRows", dayRows);
        return "pages/admin/onboarding";
    }

    public record Period(int days, String label, String cssClass) {}

    public record Group(String title, List<Row> rows) {}

    /** @param share доля от открывших мастер за период */
    public record Row(String label, long count, String share, String barStyle) {}

    public record DayRow(String day, List<Long> counts) {}
}
