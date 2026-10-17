package ru.rentoptima.onboarding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Воронка онбординга — счётчики событий по дням, как воронка виджета. Хранятся только
 * агрегаты: событие и сколько раз оно случилось за день. Кто именно — не записывается и
 * восстановить нельзя; так и оставить, иначе это станет учётом действий пользователей.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingStats {

    /** События в порядке воронки — в этом порядке их показывает страница администратора. */
    public static final List<String> EVENTS = List.of(
            "wizard_start", "branch_A", "branch_B", "branch_AB",
            "step_property", "step_platforms", "step_link", "step_page",
            "wizard_done", "wizard_skip",
            "tour_frame_1", "tour_frame_2", "tour_frame_3", "tour_frame_4", "tour_done", "tour_close",
            "check_property", "check_platform", "check_fetched", "check_booking", "check_page", "check_card",
            "check_all");

    private final JdbcTemplate jdbc;

    /** Событие случилось. Сбой счётчика работу не останавливает. */
    public void hit(String event) {
        if (!EVENTS.contains(event)) return;
        try {
            jdbc.update("""
                    INSERT INTO onboarding_funnel_daily(day, event, hits) VALUES (CURRENT_DATE, ?, 1)
                    ON CONFLICT (day, event) DO UPDATE SET hits = onboarding_funnel_daily.hits + 1
                    """, event);
        } catch (RuntimeException e) {
            log.warn("Счётчик онбординга {} не записан: {}", event, e.getMessage());
        }
    }

    /** Сумма по каждому событию за период (включительно); события без данных — с нулём. */
    public Map<String, Long> totals(LocalDate from, LocalDate to) {
        Map<String, Long> totals = new LinkedHashMap<>();
        EVENTS.forEach(e -> totals.put(e, 0L));
        jdbc.query("SELECT event, SUM(hits) FROM onboarding_funnel_daily WHERE day BETWEEN ? AND ? GROUP BY event",
                rs -> { if (totals.containsKey(rs.getString(1))) totals.put(rs.getString(1), rs.getLong(2)); }, from, to);
        return totals;
    }

    /** День → событие → число, для таблицы по дням. Дни без событий не возвращаются. */
    public Map<LocalDate, Map<String, Long>> byDay(LocalDate from, LocalDate to) {
        Map<LocalDate, Map<String, Long>> days = new LinkedHashMap<>();
        jdbc.query("SELECT day, event, hits FROM onboarding_funnel_daily WHERE day BETWEEN ? AND ? ORDER BY day DESC",
                rs -> { days.computeIfAbsent(rs.getDate(1).toLocalDate(), d -> new LinkedHashMap<>())
                        .put(rs.getString(2), rs.getLong(3)); }, from, to);
        return days;
    }
}
