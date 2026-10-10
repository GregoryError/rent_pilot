package ru.rentoptima.service;

import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Публичный адрес страницы бронирования: /b/{slug}. Латиница, цифры и дефис. */
public final class WidgetSlug {

    public static final int MIN = 3;
    public static final int MAX = 60;

    private static final Pattern VALID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    private static final Map<Character, String> TRANSLIT = Map.ofEntries(
            Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"), Map.entry('г', "g"),
            Map.entry('д', "d"), Map.entry('е', "e"), Map.entry('ё', "e"), Map.entry('ж', "zh"),
            Map.entry('з', "z"), Map.entry('и', "i"), Map.entry('й', "y"), Map.entry('к', "k"),
            Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"), Map.entry('о', "o"),
            Map.entry('п', "p"), Map.entry('р', "r"), Map.entry('с', "s"), Map.entry('т', "t"),
            Map.entry('у', "u"), Map.entry('ф', "f"), Map.entry('х', "h"), Map.entry('ц', "ts"),
            Map.entry('ч', "ch"), Map.entry('ш', "sh"), Map.entry('щ', "sch"), Map.entry('ъ', ""),
            Map.entry('ы', "y"), Map.entry('ь', ""), Map.entry('э', "e"), Map.entry('ю', "yu"),
            Map.entry('я', "ya"));

    private WidgetSlug() {}

    public static boolean valid(String slug) {
        return slug != null && slug.length() >= MIN && slug.length() <= MAX && VALID.matcher(slug).matches();
    }

    /** «Квартира на Садовой, 12» → «kvartira-na-sadovoy-12». Пустой результат — «booking». */
    public static String fromTitle(String title) {
        StringBuilder sb = new StringBuilder();
        String lower = title == null ? "" : title.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            String mapped = TRANSLIT.get(c);
            if (mapped != null) sb.append(mapped);
            else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else sb.append('-');
        }
        String slug = sb.toString().replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        if (slug.length() > MAX) slug = slug.substring(0, MAX).replaceAll("-$", "");
        return slug.length() < MIN ? "booking" : slug;
    }

    /** Подбирает свободный адрес: сам slug, затем slug-2, slug-3… */
    public static String unique(String base, Predicate<String> taken) {
        if (!taken.test(base)) return base;
        for (int n = 2; ; n++) {
            String suffix = "-" + n;
            String head = base.length() + suffix.length() > MAX
                    ? base.substring(0, MAX - suffix.length()).replaceAll("-$", "") : base;
            String candidate = head + suffix;
            if (!taken.test(candidate)) return candidate;
        }
    }
}
