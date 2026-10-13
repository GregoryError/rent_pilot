package ru.rentoptima.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Подсказки по ходу работы — данные из {@code onboarding/hints.yml}: страница, селектор,
 * ждать ли появления элемента. Здесь — только правила показа:
 * каждая подсказка один раз, не больше одной за открытие страницы и двух за день.
 */
public final class OnboardingHints {

    /** Не больше стольких новых подсказок за день. */
    public static final int PER_DAY = 2;

    public record Hint(String id, String page, String target, boolean watch) {
        /** Подходит ли адрес страницы: «*» в описании — один любой отрезок пути. */
        public boolean matches(String path) {
            return Pattern.matches(Pattern.quote(page).replace("*", "\\E[^/]+\\Q"), path);
        }
    }

    public static final List<Hint> ALL = load();

    private OnboardingHints() {}

    /**
     * Подсказки, которые можно показать на этой странице сейчас. Браузер покажет первую,
     * чей элемент найдётся, — одну.
     *
     * @param data состояние онбординга: {@code hints.shown} — что уже показано и когда
     */
    public static List<Hint> eligible(JsonNode data, String path, LocalDate today) {
        JsonNode shown = data.path("hints").path("shown");
        int todayCount = 0;
        for (JsonNode date : shown) {
            if (today.toString().equals(date.asText())) todayCount++;
        }
        if (todayCount >= PER_DAY) return List.of();
        List<Hint> result = new ArrayList<>();
        for (Hint h : ALL) {
            if (!shown.has(h.id()) && h.matches(path)) result.add(h);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Hint> load() {
        try (InputStream in = new ClassPathResource("onboarding/hints.yml").getInputStream()) {
            List<Hint> hints = new ArrayList<>();
            for (Map<String, Object> y : (List<Map<String, Object>>) new Yaml().load(in)) {
                hints.add(new Hint(String.valueOf(y.get("id")), String.valueOf(y.get("page")),
                        String.valueOf(y.get("target")), Boolean.TRUE.equals(y.get("watch"))));
            }
            return List.copyOf(hints);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать onboarding/hints.yml: " + e.getMessage(), e);
        }
    }
}
