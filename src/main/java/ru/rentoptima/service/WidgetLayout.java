package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Раскладка и оформление виджета бронирования — {@code booking_widgets.config_json}.
 * <p>
 * Виджет собирается из блоков ({@link #BLOCKS}). Пресет задаёт, как они расставлены:
 * <ul>
 *   <li>{@code split} — содержимое слева, календарь и форма справа; на узком экране колонкой;</li>
 *   <li>{@code vertical} — одна колонка: для сайдбара и телефона;</li>
 *   <li>{@code horizontal} — полоса «даты · гости · кнопка», бронирование в окне;</li>
 *   <li>{@code compact} — цена «от» и кнопка, бронирование в окне;</li>
 *   <li>{@code custom} — хозяин расставил блоки сам: до трёх колонок для широкого экрана
 *       и отдельный порядок для узкого.</li>
 * </ul>
 * Как пресет превращается в сетку, знает сам виджет (widget/src/layout.js); здесь —
 * только проверка того, что сохраняется и отдаётся: в config_json не попадает ничего,
 * кроме известных ключей, блоков и значений. Всё, что не распознано, отбрасывается.
 */
public final class WidgetLayout {

    public static final List<String> BLOCKS = List.of("gallery", "title", "calendar", "guests", "summary",
            "form", "description", "amenities", "rules", "contacts", "map");
    /** Без этих блоков забронировать нельзя — скрыть их не даём. */
    public static final Set<String> REQUIRED = Set.of("calendar", "guests", "summary", "form");
    public static final List<String> PRESETS = List.of("split", "vertical", "horizontal", "compact", "custom");
    public static final List<String> FONTS = List.of("system", "inter", "manrope", "montserrat", "lora");
    public static final String DEFAULT_PRESET = "split";
    public static final String DEFAULT_ACCENT = "#0f766e";
    public static final int DEFAULT_RADIUS = 14;
    public static final int MAX_RADIUS = 24;
    public static final int MAX_COLUMNS = 3;
    public static final int MAX_AMENITIES = 30;

    private static final Pattern HEX = Pattern.compile("#[0-9a-f]{6}");
    /** Карты, на которые можно сослаться: ссылка открывается у гостя, чужих доменов тут быть не должно. */
    private static final List<String> MAP_HOSTS = List.of("yandex.ru", "yandex.com", "yandex.by", "yandex.kz",
            "2gis.ru", "go.2gis.com", "google.com", "google.ru", "maps.app.goo.gl", "goo.gl");

    private WidgetLayout() {}

    /**
     * Приводит config_json к допустимому виду. null и мусор дают настройки по умолчанию.
     */
    public static ObjectNode normalize(JsonNode raw) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode out = f.objectNode();
        JsonNode in = raw != null && raw.isObject() ? raw : f.objectNode();

        String preset = in.path("preset").asText("");
        out.put("preset", PRESETS.contains(preset) ? preset : DEFAULT_PRESET);

        ArrayNode hidden = out.putArray("hidden");
        for (String block : blocks(in.path("hidden"))) {
            if (!REQUIRED.contains(block)) hidden.add(block);
        }

        JsonNode custom = in.path("custom");
        if (custom.isObject()) {
            ObjectNode c = out.putObject("custom");
            Set<String> used = new LinkedHashSet<>();
            ArrayNode top = c.putArray("top");
            for (String block : blocks(custom.path("top"))) {
                if (used.add(block)) top.add(block);
            }
            ArrayNode cols = c.putArray("cols");
            JsonNode rawCols = custom.path("cols");
            for (int i = 0; rawCols.isArray() && i < rawCols.size() && cols.size() < MAX_COLUMNS; i++) {
                ArrayNode col = f.arrayNode();
                for (String block : blocks(rawCols.get(i))) {
                    if (used.add(block)) col.add(block);
                }
                if (!col.isEmpty()) cols.add(col);
            }
            // Блок, который хозяин никуда не поставил, не теряется — уходит в конец последней колонки
            ArrayNode last = cols.isEmpty() ? cols.addArray() : (ArrayNode) cols.get(cols.size() - 1);
            for (String block : BLOCKS) {
                if (used.add(block)) last.add(block);
            }
            ArrayNode mobile = c.putArray("mobile");
            Set<String> order = new LinkedHashSet<>(blocks(custom.path("mobile")));
            order.addAll(BLOCKS);
            order.forEach(mobile::add);
        }

        JsonNode theme = in.path("theme");
        ObjectNode t = out.putObject("theme");
        String accent = theme.path("accent").asText("").toLowerCase(Locale.ROOT);
        t.put("accent", HEX.matcher(accent).matches() ? accent : DEFAULT_ACCENT);
        int radius = theme.path("radius").asInt(DEFAULT_RADIUS);
        t.put("radius", Math.max(0, Math.min(MAX_RADIUS, radius)));
        String font = theme.path("font").asText("");
        t.put("font", FONTS.contains(font) ? font : "system");
        return out;
    }

    /** Известные блоки из JSON-массива, без повторов, в порядке появления. */
    private static List<String> blocks(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array == null || !array.isArray()) return result;
        for (JsonNode n : array) {
            String block = n.asText("");
            if (BLOCKS.contains(block) && !result.contains(block)) result.add(block);
        }
        return result;
    }

    public static boolean hidden(JsonNode normalized, String block) {
        for (JsonNode n : normalized.path("hidden")) {
            if (block.equals(n.asText())) return true;
        }
        return false;
    }

    /** Удобства: по одному в строке, без пустых и повторов, не длиннее 60 символов. */
    public static List<String> amenities(String text) {
        Set<String> items = new LinkedHashSet<>();
        if (text != null) {
            for (String line : text.split("\\R")) {
                String item = line.trim();
                if (item.isEmpty()) continue;
                items.add(item.length() > 60 ? item.substring(0, 60) : item);
                if (items.size() >= MAX_AMENITIES) break;
            }
        }
        return new ArrayList<>(items);
    }

    /**
     * Ссылка на карту: только https и только известные картографические сервисы.
     *
     * @return ссылка или null, если она не подходит
     */
    public static String mapUrl(String raw) {
        if (raw == null) return null;
        String url = raw.trim();
        if (url.isEmpty() || url.length() > 500 || url.matches(".*[\\s\"'<>].*")) return null;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            for (String allowed : MAP_HOSTS) {
                if (host.equals(allowed) || host.endsWith("." + allowed)) return url;
            }
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- Контраст (WCAG 2.1)

    /** Относительная яркость цвета #rrggbb. */
    static double luminance(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** Контраст двух цветов, от 1 до 21. */
    public static double contrast(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** Фон карточки виджета в светлой и тёмной теме — те же значения, что в styles.css. */
    public static final String BG_LIGHT = "#ffffff";
    public static final String BG_DARK = "#17181b";

    /**
     * Предупреждение о цвете для хозяина или null, если цвет годится.
     * <p>
     * Текст на кнопке виджет сам делает чёрным или белым — он читается всегда (у любого
     * цвета контраст хотя бы с одним из них не ниже 4,5). Проблемой остаётся только
     * кнопка, сливающаяся с фоном: WCAG требует для элементов управления контраст 3:1.
     *
     * @param mode light | dark | auto
     */
    public static String accentWarning(String accent, String mode) {
        boolean light = !"dark".equals(mode);
        boolean dark = "dark".equals(mode) || "auto".equals(mode);
        boolean lowLight = light && contrast(accent, BG_LIGHT) < 3;
        boolean lowDark = dark && contrast(accent, BG_DARK) < 3;
        if (lowLight && lowDark) return "Цвет кнопки плохо виден и на светлом, и на тёмном фоне — выберите другой";
        if (lowLight) return "Цвет слишком светлый: кнопка сливается с белым фоном. Выберите цвет темнее";
        if (lowDark) return "Цвет слишком тёмный: в тёмной теме кнопка сливается с фоном. Выберите цвет светлее"
                + ("auto".equals(mode) ? " или оставьте только светлую тему" : "");
        return null;
    }
}
