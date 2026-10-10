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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.service.WidgetBookingService;
import ru.rentoptima.service.WidgetConfigService;
import ru.rentoptima.service.WidgetLayout;
import ru.rentoptima.service.WidgetOrigins;

import java.net.URI;
import java.util.Map;

/**
 * Публичные страницы виджета бронирования, без авторизации.
 * <ul>
 *   <li>{@code GET /b/{slug}} — страница бронирования: ссылка для мессенджеров, с
 *       превью (OpenGraph) и настройками виджета, встроенными в страницу;</li>
 *   <li>{@code GET /b/{slug}/embed} — она же для вставки во фрейм на сайт хозяина
 *       (конструкторы сайтов, которые режут скрипты): сообщает родителю свою высоту;</li>
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

    private final WidgetBookingService widgets;
    private final WidgetConfigService configs;

    @GetMapping("/b/{slug}")
    public String page(@PathVariable String slug, Model model, HttpServletRequest request) {
        fill(model, bySlug(slug), request, false);
        return "pages/widget/page";
    }

    /** Страница для фрейма. Встраивать можно только на наш домен и сайты, разрешённые хозяином. */
    @GetMapping("/b/{slug}/embed")
    public String embed(@PathVariable String slug, Model model, HttpServletRequest request,
                        HttpServletResponse response) {
        BookingWidget w = bySlug(slug);
        response.setHeader("Content-Security-Policy", WidgetOrigins.frameAncestors(w.getAllowedOrigins()));
        fill(model, w, request, true);
        return "pages/widget/page";
    }

    private void fill(Model model, BookingWidget w, HttpServletRequest request, boolean embed) {
        String baseUrl = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        var theme = WidgetLayout.normalize(w.getConfigJson()).path("theme");
        String accent = theme.path("accent").asText();
        // Выбранный шрифт подгружается заранее: тогда он успевает к первой отрисовке
        String font = theme.path("font").asText();
        model.addAttribute("fontFiles", "system".equals(font) ? java.util.List.of() : java.util.List.of(
                baseUrl + "/fonts/" + font + "-cyrillic-wght-normal.woff2",
                baseUrl + "/fonts/" + font + "-latin-wght-normal.woff2"));
        model.addAttribute("widget", w);
        model.addAttribute("embed", embed);
        model.addAttribute("configJson", configs.inlineJson(w, baseUrl));
        model.addAttribute("pageUrl", baseUrl + "/b/" + w.getSlug());
        model.addAttribute("shareImage", configs.shareImage(w, baseUrl));
        model.addAttribute("shareDescription", shareDescription(w));
        model.addAttribute("pageClass", (embed ? "embed " : "") + "page page--" + themeOf(w));
        model.addAttribute("themeColor", accent);
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

    private BookingWidget bySlug(String slug) {
        return widgets.findBySlug(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private BookingWidget bySecret(String secret) {
        return widgets.findActive(secret).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String themeOf(BookingWidget w) {
        String theme = w.getTheme();
        return "dark".equals(theme) || "auto".equals(theme) ? theme : "light";
    }

    /** Описание для превью ссылки: начало описания жилья, иначе общая фраза. */
    static String shareDescription(BookingWidget w) {
        String d = w.getDescription();
        if (d == null || d.isBlank()) return "Бронирование напрямую у хозяина, без комиссии площадок.";
        String flat = d.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 197) + "…" : flat;
    }
}
