package ru.rentoptima.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionCalendarService {

    // In-memory cache: date -> holiday name
    private final Map<LocalDate, String> holidays = new ConcurrentHashMap<>();
    private final RestTemplate restTemplate = new RestTemplate();

    @PostConstruct
    public void init() {
        loadYear(LocalDate.now().getYear());
        loadYear(LocalDate.now().getYear() + 1);
    }

    // Reload every month
    @Scheduled(cron = "0 0 3 1 * *")
    public void scheduledReload() {
        holidays.clear();
        init();
    }

    /**
     * Parse xmlcalendar.ru API response.
     * Format: CSV-like data with days marked as holidays.
     * URL: https://xmlcalendar.ru/data/ru/{year}/calendar.json
     */
    private void loadYear(int year) {
        try {
            String url = "https://xmlcalendar.ru/data/ru/" + year + "/calendar.json";
            var response = restTemplate.getForObject(url, Map.class);
            if (response == null) return;

            // Response structure: { "months": [ { "month": 1, "days": "1,2,3*,8" }, ... ] }
            // Days ending with * are shortened pre-holiday days
            // Regular numbers are holidays/weekends
            Object monthsObj = response.get("months");
            if (!(monthsObj instanceof List<?> months)) return;

            for (Object mObj : months) {
                if (!(mObj instanceof Map<?, ?> monthMap)) continue;
                int month = ((Number) monthMap.get("month")).intValue();
                String daysStr = String.valueOf(monthMap.get("days"));

                for (String dayToken : daysStr.split(",")) {
                    String cleaned = dayToken.trim().replace("*", "").replace("+", "");
                    if (cleaned.isEmpty()) continue;
                    try {
                        int day = Integer.parseInt(cleaned);
                        LocalDate date = LocalDate.of(year, month, day);
                        // Check if it's a weekday holiday (not just a regular weekend)
                        int dow = date.getDayOfWeek().getValue();
                        if (dow <= 5) { // Mon-Fri that's marked as holiday
                            holidays.put(date, getHolidayName(month, day));
                        }
                    } catch (Exception e) {
                        // skip invalid entries
                    }
                }
            }

            log.info("Loaded production calendar for {}: {} weekday holidays", year, holidays.size());
        } catch (Exception e) {
            log.warn("Failed to load production calendar for {}: {}", year, e.getMessage());
            // Fallback: add known Russian holidays manually
            addFallbackHolidays(year);
        }
    }

    private void addFallbackHolidays(int year) {
        // Major Russian public holidays
        holidays.put(LocalDate.of(year, 1, 1), "Новый год");
        holidays.put(LocalDate.of(year, 1, 2), "Новогодние каникулы");
        holidays.put(LocalDate.of(year, 1, 3), "Новогодние каникулы");
        holidays.put(LocalDate.of(year, 1, 4), "Новогодние каникулы");
        holidays.put(LocalDate.of(year, 1, 5), "Новогодние каникулы");
        holidays.put(LocalDate.of(year, 1, 6), "Новогодние каникулы");
        holidays.put(LocalDate.of(year, 1, 7), "Рождество");
        holidays.put(LocalDate.of(year, 1, 8), "Новогодние каникулы");
        holidays.put(LocalDate.of(year, 2, 23), "День защитника Отечества");
        holidays.put(LocalDate.of(year, 3, 8), "Международный женский день");
        holidays.put(LocalDate.of(year, 5, 1), "Праздник Весны и Труда");
        holidays.put(LocalDate.of(year, 5, 9), "День Победы");
        holidays.put(LocalDate.of(year, 6, 12), "День России");
        holidays.put(LocalDate.of(year, 11, 4), "День народного единства");
        log.info("Loaded fallback holidays for {}", year);
    }

    private String getHolidayName(int month, int day) {
        if (month == 1 && day <= 8) return day == 7 ? "Рождество" : "Новогодние каникулы";
        if (month == 2 && day == 23) return "День защитника Отечества";
        if (month == 3 && day == 8) return "Международный женский день";
        if (month == 5 && day == 1) return "Праздник Весны и Труда";
        if (month == 5 && day == 9) return "День Победы";
        if (month == 6 && day == 12) return "День России";
        if (month == 11 && day == 4) return "День народного единства";
        return "Выходной (перенос)";
    }

    public Map<LocalDate, String> getHolidaysInRange(LocalDate from, LocalDate to) {
        Map<LocalDate, String> result = new LinkedHashMap<>();
        for (var entry : holidays.entrySet()) {
            if (!entry.getKey().isBefore(from) && !entry.getKey().isAfter(to)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public boolean isHoliday(LocalDate date) {
        return holidays.containsKey(date);
    }

    public boolean isWeekendOrHoliday(LocalDate date) {
        return date.getDayOfWeek().getValue() >= 6 || holidays.containsKey(date);
    }
}
