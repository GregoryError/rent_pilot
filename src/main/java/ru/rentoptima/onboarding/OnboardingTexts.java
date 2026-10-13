package ru.rentoptima.onboarding;

import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Все тексты онбординга — в одном файле {@code onboarding/texts_ru.yml}. Язык пока один;
 * чтобы добавить английский, нужен {@code texts_en.yml} с теми же ключами и выбор языка
 * здесь — шаблоны и код ключей не меняют.
 * <p>
 * В шаблоне: {@code ${t['start.title']}}. Текст с подстановкой — {@link #format}.
 */
public final class OnboardingTexts {

    public static final Map<String, String> RU = load("ru");

    private static final String[] MONTHS_GENITIVE = {"января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"};

    private OnboardingTexts() {}

    public static String get(String key) {
        String text = RU.get(key);
        if (text == null) throw new IllegalArgumentException("Нет текста онбординга: " + key);
        return text;
    }

    public static String format(String key, Object... args) {
        return MessageFormat.format(get(key).replace("'", "''"), args);
    }

    /** «1 занятый период», «3 занятых периода», «7 занятых периодов». */
    public static String periods(int n) {
        int d = n % 10, h = n % 100;
        String form = d == 1 && h != 11 ? "занятый период"
                : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "занятых периода" : "занятых периодов";
        return n + " " + form;
    }

    /** «до марта»; год добавляется, только если он не текущий: «до марта 2027». */
    public static String until(LocalDate date, LocalDate today) {
        String month = MONTHS_GENITIVE[date.getMonthValue() - 1];
        return "до " + month + (date.getYear() == today.getYear() ? "" : " " + date.getYear());
    }

    /** Человеческий итог проверки ссылки с площадки. */
    public static String feedMessage(FeedChecker.Result r, LocalDate today) {
        return switch (r.outcome()) {
            case FOUND -> format("link.found", periods(r.periods()), until(r.until(), today));
            case EMPTY -> get("link.empty");
            case NOT_CALENDAR -> get("link.notCalendar");
            case UNREACHABLE -> get("link.unreachable");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> load(String lang) {
        try (InputStream in = new ClassPathResource("onboarding/texts_" + lang + ".yml").getInputStream()) {
            Map<String, String> flat = new LinkedHashMap<>();
            flatten("", (Map<String, Object>) new Yaml().load(in), flat);
            return Map.copyOf(flat);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать onboarding/texts_" + lang + ".yml: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> node, Map<String, String> out) {
        for (Map.Entry<String, Object> e : node.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> m) flatten(key, (Map<String, Object>) m, out);
            else out.put(key, String.valueOf(e.getValue()).trim());
        }
    }
}
