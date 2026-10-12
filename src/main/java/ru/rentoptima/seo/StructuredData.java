package ru.rentoptima.seo;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Микроразметка schema.org в формате JSON-LD. Чистые функции без БД: на вход данные
 * страницы, на выход строка для {@code <script type="application/ld+json">}.
 * <p>
 * Правила, которые здесь держатся кодом:
 * <ul>
 *   <li>в разметке только то, что есть на самой странице видимым текстом;</li>
 *   <li>никаких {@code aggregateRating} и {@code review}: публичных отзывов у нас нет,
 *       выдуманный рейтинг — прямой путь под санкции поисковика;</li>
 *   <li>адрес жилья — только город: точный адрес хозяин гостям до брони не открывает.</li>
 * </ul>
 */
public final class StructuredData {

    public static final String BRAND = "OptiRent";
    /** Как бренд пишут и ищут по-русски. */
    public static final List<String> BRAND_ALIASES = List.of("ОптиРент", "Оптирент", "Opti Rent");

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Question(String question, String answer) {}

    /** Звено хлебных крошек: подпись и путь на сайте («/instrukcii»). */
    public record Crumb(String name, String path) {}

    /** Жильё на странице бронирования. Пустые и null-поля в разметку не попадают. */
    public record Lodging(String name, String url, String description, List<String> images, String city,
                          List<String> amenities, BigDecimal priceFrom, String checkinTime,
                          String checkoutTime, boolean petsAllowed) {}

    private StructuredData() {}

    public static String organization(String baseUrl) {
        Map<String, Object> o = node("Organization");
        o.put("@id", baseUrl + "/#organization");
        o.put("name", BRAND);
        o.put("alternateName", BRAND_ALIASES);
        o.put("url", baseUrl + "/");
        o.put("logo", baseUrl + "/icons/icon-512.png");
        return json(o);
    }

    public static String website(String baseUrl) {
        Map<String, Object> o = node("WebSite");
        o.put("@id", baseUrl + "/#website");
        o.put("name", BRAND);
        o.put("alternateName", BRAND_ALIASES);
        o.put("url", baseUrl + "/");
        o.put("inLanguage", "ru");
        o.put("publisher", Map.of("@id", baseUrl + "/#organization"));
        return json(o);
    }

    /** Сам продукт. Цена 0 ₽ — сервис бесплатный; станет платным — убрать {@code offers}. */
    public static String softwareApplication(String baseUrl, String path, String description) {
        Map<String, Object> o = node("SoftwareApplication");
        o.put("name", BRAND);
        o.put("alternateName", BRAND_ALIASES);
        o.put("url", baseUrl + path);
        o.put("description", description);
        o.put("applicationCategory", "BusinessApplication");
        o.put("operatingSystem", "Web");
        o.put("inLanguage", "ru");
        Map<String, Object> offer = node("Offer");
        offer.remove("@context");
        offer.put("price", "0");
        offer.put("priceCurrency", "RUB");
        o.put("offers", offer);
        o.put("publisher", Map.of("@id", baseUrl + "/#organization"));
        return json(o);
    }

    /** Вопросы и ответы. Те же тексты должны быть видны на странице — не только в разметке. */
    public static String faqPage(List<Question> questions) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Question q : questions) {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("@type", "Answer");
            answer.put("text", q.answer());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("@type", "Question");
            item.put("name", q.question());
            item.put("acceptedAnswer", answer);
            items.add(item);
        }
        Map<String, Object> o = node("FAQPage");
        o.put("mainEntity", items);
        return json(o);
    }

    /** Хлебные крошки от главной до текущей страницы включительно. */
    public static String breadcrumbs(String baseUrl, List<Crumb> crumbs) {
        List<Map<String, Object>> items = new ArrayList<>();
        int position = 1;
        for (Crumb c : crumbs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("@type", "ListItem");
            item.put("position", position++);
            item.put("name", c.name());
            item.put("item", baseUrl + ("/".equals(c.path()) ? "/" : c.path()));
            items.add(item);
        }
        Map<String, Object> o = node("BreadcrumbList");
        o.put("itemListElement", items);
        return json(o);
    }

    public static String lodging(Lodging l) {
        Map<String, Object> o = node("LodgingBusiness");
        o.put("name", l.name());
        o.put("url", l.url());
        if (present(l.description())) o.put("description", l.description());
        if (l.images() != null && !l.images().isEmpty()) o.put("image", l.images());
        if (present(l.city())) {
            Map<String, Object> address = new LinkedHashMap<>();
            address.put("@type", "PostalAddress");
            address.put("addressLocality", l.city().trim());
            address.put("addressCountry", "RU");
            o.put("address", address);
        }
        if (l.amenities() != null && !l.amenities().isEmpty()) {
            List<Map<String, Object>> features = new ArrayList<>();
            for (String name : l.amenities()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("@type", "LocationFeatureSpecification");
                f.put("name", name);
                f.put("value", true);
                features.add(f);
            }
            o.put("amenityFeature", features);
        }
        if (l.priceFrom() != null && l.priceFrom().signum() > 0) {
            o.put("priceRange", "от " + rubles(l.priceFrom()) + " ₽ за ночь");
            o.put("currenciesAccepted", "RUB");
        }
        if (present(l.checkinTime())) o.put("checkinTime", l.checkinTime());
        if (present(l.checkoutTime())) o.put("checkoutTime", l.checkoutTime());
        o.put("petsAllowed", l.petsAllowed());
        return json(o);
    }

    private static Map<String, Object> node(String type) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("@context", "https://schema.org");
        o.put("@type", type);
        return o;
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static String rubles(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        return new DecimalFormat("#,##0", symbols).format(amount.setScale(0, RoundingMode.HALF_UP));
    }

    /**
     * Строка для вставки в {@code <script>}: «<» экранируется, чтобы текст хозяина с
     * «</script>» не закрыл тег.
     */
    static String json(Map<String, Object> node) {
        try {
            return JSON.writeValueAsString(node)
                    .replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
