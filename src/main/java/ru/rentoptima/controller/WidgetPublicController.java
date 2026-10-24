package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import ru.rentoptima.auth.EmailVerificationService;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.entity.WidgetQrLink;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.seo.StructuredData;
import ru.rentoptima.seo.SeoFilter;
import ru.rentoptima.seo.SiteSettings;
import ru.rentoptima.service.WidgetBookingService;
import ru.rentoptima.service.WidgetCardService;
import ru.rentoptima.service.WidgetConfigService;
import ru.rentoptima.service.WidgetLayout;
import ru.rentoptima.service.WidgetOrigins;

import java.net.URI;
import java.time.LocalDate;
import java.util.Map;

/**
 * Публичные страницы виджета бронирования, без авторизации.
 * <ul>
 *   <li>{@code GET /b/{slug}} — страница бронирования: ссылка для мессенджеров, с
 *       превью (OpenGraph) и настройками виджета, встроенными в страницу;</li>
 *   <li>{@code GET /b/{slug}/embed} — она же для вставки во фрейм на сайт хозяина
 *       (конструкторы сайтов, которые режут скрипты): сообщает родителю свою высоту;</li>
 *   <li>{@code GET /q/{code}} — короткий адрес с печатной карточки: редирект на страницу
 *       бронирования с промокодом и меткой источника;</li>
 *   <li>прежние адреса {@code /book/{secret}} и {@code /widget/{secret}} — постоянные
 *       редиректы на новые. Срока у них нет: ссылки, которые хозяева уже разослали
 *       гостям и вставили на сайты, должны работать всегда. Не удалять.</li>
 * </ul>
 * Прежняя вставка скриптом ({@code /widget.js} + {@code <div data-secret>}) тоже жива:
 * статический {@code widget.js} подменяет её новым компонентом, а адрес виджета по
 * секрету узнаёт у {@link #slug}.
 */
@Controller
@RequiredArgsConstructor
public class WidgetPublicController {

    /** Блоки виджета, которые на странице /b/<slug> заменяет серверный текст. */
    static final java.util.List<String> PAGE_TEXT_BLOCKS = java.util.List.of("description", "amenities", "rules");

    private final WidgetBookingService widgets;
    private final WidgetConfigService configs;
    private final EmailVerificationService verification;
    private final WidgetCardService cards;
    private final SiteSettings site;
    private final UnitTypeRepository unitTypeRepo;
    private final PropertyRepository propertyRepo;
    /** Адреса удалённых аккаунтов — 410 (V49). */
    private final ru.rentoptima.account.GoneLinks gone;

    @GetMapping("/b/{slug}")
    public String page(@PathVariable String slug, Model model, HttpServletRequest request,
                       HttpServletResponse response) {
        BookingWidget w = bySlug(slug);
        if (inactive(w)) return inactivePage(model, w, response);
        // В поиске страница — только с согласия хозяина
        if (!Boolean.TRUE.equals(w.getSearchIndexed())) response.setHeader(SeoFilter.HEADER, SeoFilter.NOINDEX);
        fill(model, w, request, false);
        return "pages/widget/page";
    }

    /** Страница для фрейма. Встраивать можно только на наш домен и сайты, разрешённые хозяином. */
    @GetMapping("/b/{slug}/embed")
    public String embed(@PathVariable String slug, Model model, HttpServletRequest request,
                        HttpServletResponse response) {
        BookingWidget w = bySlug(slug);
        response.setHeader("Content-Security-Policy", WidgetOrigins.frameAncestors(w.getAllowedOrigins()));
        if (inactive(w)) return inactivePage(model, w, response);
        response.setHeader(SeoFilter.HEADER, SeoFilter.NOINDEX);
        fill(model, w, request, true);
        return "pages/widget/page";
    }

    private void fill(Model model, BookingWidget w, HttpServletRequest request, boolean embed) {
        String baseUrl = site.linkBase(request);
        var theme = WidgetLayout.normalize(w.getConfigJson()).path("theme");
        String accent = theme.path("accent").asText();
        // Выбранный шрифт подгружается заранее: тогда он успевает к первой отрисовке
        String font = theme.path("font").asText();
        model.addAttribute("fontFiles", "system".equals(font) ? java.util.List.of() : java.util.List.of(
                baseUrl + "/fonts/" + font + "-cyrillic-wght-normal.woff2",
                baseUrl + "/fonts/" + font + "-latin-wght-normal.woff2"));
        model.addAttribute("widget", w);
        model.addAttribute("embed", embed);
        // На нашей странице описание, удобства и правила выводит сервер (блок about ниже) —
        // виджету они передаются скрытыми. Во фрейме на сайте хозяина серверного блока нет,
        // и виджет показывает всё, как настроено в конструкторе.
        model.addAttribute("configJson", configs.inlineJson(w, baseUrl, embed ? java.util.List.of() : PAGE_TEXT_BLOCKS));
        // Во фрейме Метрику не грузим вовсе: спросить согласие там негде
        model.addAttribute("metrika", w.getMetrikaId() == null || w.getMetrikaId().isBlank() ? null
                : embed ? "off" : "ask");
        model.addAttribute("pageUrl", site.canonical(request, "/b/" + w.getSlug()));
        model.addAttribute("indexed", !embed && Boolean.TRUE.equals(w.getSearchIndexed()));
        model.addAttribute("pageTitle", pageTitle(w));
        model.addAttribute("shareImage", configs.shareImage(w, baseUrl));
        model.addAttribute("shareDescription", shareDescription(w));
        // Текст для поисковика и для тех, у кого не загрузился скрипт: то же, что показывает
        // виджет, но в самом HTML. Блоки, скрытые хозяином в конструкторе, не выводятся.
        java.util.Set<String> hidden = new java.util.HashSet<>();
        WidgetLayout.normalize(w.getConfigJson()).path("hidden").forEach(n -> hidden.add(n.asText()));
        model.addAttribute("about", embed ? null : new About(
                hidden.contains("description") ? null : blankToNull(w.getDescription()),
                hidden.contains("amenities") ? java.util.List.of() : WidgetLayout.amenities(w.getAmenities()),
                hidden.contains("rules") ? null : blankToNull(w.getRules()),
                hidden.contains("rules") ? null : blankToNull(w.getCancellationPolicy()),
                blankToNull(w.getAddressHint())));
        model.addAttribute("jsonLd", embed ? null : StructuredData.lodging(lodging(w, hidden, baseUrl,
                site.canonical(request, "/b/" + w.getSlug()))));
        model.addAttribute("pageClass", (embed ? "embed " : "") + "page page--" + themeOf(w));
        model.addAttribute("themeColor", accent);
    }

    // --- Короткие адреса печатной карточки

    /**
     * QR с карточки и ссылка из сообщения гостю. Редирект временный (302) и не кэшируется:
     * куда он ведёт, хозяин может поменять уже после печати — сменить или отключить
     * промокод. Отозванная ссылка всё равно открывает страницу бронирования.
     * Адрес в Location — полный, от {@code APP_BASE_URL}: карточки, напечатанные со старым
     * доменом (staging.optirent.ru/q/…), ведут сразу на основной, без второго редиректа.
     */
    @GetMapping("/q/{code}")
    public ResponseEntity<Void> shortLink(@PathVariable String code, HttpServletRequest request) {
        WidgetQrLink link = cards.findLink(code)
                .filter(l -> !WidgetQrLink.KIND_FEEDBACK.equals(l.getKind()))
                .orElseThrow(() -> new ResponseStatusException(gone.isGone(ru.rentoptima.account.GoneLinks.Kind.QR, code)
                        ? HttpStatus.GONE : HttpStatus.NOT_FOUND));
        // Ссылка есть, а страницы нет — её удалили или отключили: 410, как у /b/
        BookingWidget w = widgets.findById(link.getWidgetId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE));
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, site.linkBase(request) + cards.target(link, w, LocalDate.now()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    // --- Прежние адреса

    @GetMapping("/book/{secret}")
    public ResponseEntity<Void> legacyPage(@PathVariable String secret, HttpServletRequest request) {
        return moved("/b/" + bySecret(secret).getSlug(), request);
    }

    @GetMapping("/widget/{secret}")
    public ResponseEntity<Void> legacyFrame(@PathVariable String secret, HttpServletRequest request) {
        return moved("/b/" + bySecret(secret).getSlug() + "/embed", request);
    }

    /**
     * Адрес виджета по секрету — для прежней вставки скриптом. CORS — как у остального
     * API: только сайты, разрешённые хозяином.
     */
    @GetMapping("/widget/{secret}/slug")
    @ResponseBody
    public Map<String, String> slug(@PathVariable String secret) {
        return Map.of("slug", bySecret(secret).getSlug());
    }

    /** 301 с сохранением параметров: ссылка «на конкретные даты» остаётся ссылкой на эти даты. */
    private static ResponseEntity<Void> moved(String path, HttpServletRequest request) {
        String query = request.getQueryString();
        String target = request.getContextPath() + path + (query == null ? "" : "?" + query);
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, URI.create(target).toASCIIString())
                .build();
    }

    /**
     * Хозяин новой страницы не подтвердил почту — гости видят «скоро заработает». Сам хозяин
     * (вошедший в свой кабинет) видит страницу как есть.
     */
    private boolean inactive(BookingWidget w) {
        return verification.pageInactive(w.getTenantId()) && !w.getTenantId().equals(AuthContext.tenantIdOrNull());
    }

    private static String inactivePage(Model model, BookingWidget w, HttpServletResponse response) {
        response.setHeader(SeoFilter.HEADER, SeoFilter.NOINDEX);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        model.addAttribute("title", w.getTitle());
        return "pages/widget/inactive";
    }

    /** Нет такой страницы — 404; была, но хозяин удалил — 410: поисковик уберёт её быстрее. */
    private BookingWidget bySlug(String slug) {
        return widgets.findBySlug(slug).orElseThrow(() -> new ResponseStatusException(
                widgets.wasDeleted(slug) || gone.isGone(ru.rentoptima.account.GoneLinks.Kind.PAGE, slug) ? HttpStatus.GONE : HttpStatus.NOT_FOUND));
    }

    /**
     * Жильё для микроразметки — то же, что гость видит на странице: скрытые хозяином блоки
     * и цена при выключенном «показывать цену» в разметку не попадают. Из адреса — только
     * город объекта.
     */
    private StructuredData.Lodging lodging(BookingWidget w, java.util.Set<String> hidden, String baseUrl, String url) {
        UnitType unit = unitTypeRepo.findById(w.getUnitTypeId()).orElse(null);
        String city = unit == null ? null
                : propertyRepo.findById(unit.getPropertyId()).map(Property::getCity).orElse(null);
        java.math.BigDecimal priceFrom = null;
        if (unit != null && Boolean.TRUE.equals(w.getShowPrice())) {
            priceFrom = unit.getBasePrice();
            if (unit.getWeekendPrice() != null && (priceFrom == null || unit.getWeekendPrice().compareTo(priceFrom) < 0)) {
                priceFrom = unit.getWeekendPrice();
            }
        }
        return new StructuredData.Lodging(w.getTitle().trim(), url,
                hidden.contains("description") ? null : blankToNull(w.getDescription()),
                hidden.contains("gallery") ? java.util.List.of() : configs.imageUrls(w, baseUrl), city,
                hidden.contains("amenities") ? java.util.List.of() : WidgetLayout.amenities(w.getAmenities()),
                priceFrom, w.getCheckinTime().toString(), w.getCheckoutTime().toString(),
                Boolean.TRUE.equals(w.getPetsAllowed()));
    }

    /** Серверный текст страницы; пустые части не выводятся. */
    public record About(String description, java.util.List<String> amenities, String rules,
                        String cancellation, String address) {
        public boolean isEmpty() {
            return description == null && amenities.isEmpty() && rules == null && cancellation == null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Заголовок вкладки и сниппета: название жилья и что тут делают; длинное название — как есть. */
    static String pageTitle(BookingWidget w) {
        String title = w.getTitle().trim();
        String suffix = " — бронирование напрямую";
        return title.length() + suffix.length() <= 60 ? title + suffix : title;
    }

    private BookingWidget bySecret(String secret) {
        return widgets.findActive(secret).orElseThrow(() -> new ResponseStatusException(
                gone.isGone(ru.rentoptima.account.GoneLinks.Kind.SECRET, secret) ? HttpStatus.GONE : HttpStatus.NOT_FOUND));
    }

    private static String themeOf(BookingWidget w) {
        String theme = w.getTheme();
        return "dark".equals(theme) || "auto".equals(theme) ? theme : "light";
    }

    /** Описание для превью ссылки: начало описания жилья, иначе общая фраза. */
    static String shareDescription(BookingWidget w) {
        String d = w.getDescription();
        // Без описания фраза общая — название жилья делает её своей у каждой страницы
        if (d == null || d.isBlank()) {
            return w.getTitle().trim() + " — бронирование напрямую у хозяина, без комиссии площадок.";
        }
        String flat = d.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 197) + "…" : flat;
    }
}
