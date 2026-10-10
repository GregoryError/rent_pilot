package ru.rentoptima.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.service.PublicRateLimiter;
import ru.rentoptima.service.WidgetBookingService;
import ru.rentoptima.service.WidgetNotifier;
import ru.rentoptima.service.WidgetOrigins;
import ru.rentoptima.service.WidgetPricing;
import ru.rentoptima.service.WidgetBookingService.StayRequest;
import ru.rentoptima.service.WidgetBookingService.Submission;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Публичная часть виджета бронирования (блок 4.9), без авторизации.
 * <p>
 * Три способа показа используют один и тот же интерфейс (static/js/widget-core.js)
 * и одни и те же JSON-эндпоинты:
 * <ul>
 *   <li>{@code GET /book/{secret}} — полная страница для отправки ссылкой в мессенджер;</li>
 *   <li>{@code GET /widget/{secret}} — минимальная обёртка для iframe;</li>
 *   <li>{@code /widget.js} — статический загрузчик для вставки скриптом на чужой сайт.</li>
 * </ul>
 * JSON-эндпоинты без CSRF: сессии и cookie здесь не участвуют, доступ определяется
 * секретом виджета. С чужих сайтов они доступны только из списка разрешённых у
 * виджета (WidgetCorsConfig), тем же списком ограничено встраивание в iframe
 * (frame-ancestors). Защита от спама — лимит запросов по IP и honeypot.
 * <p>
 * Это прежний виджет (блок 4.9). Новый работает через WidgetApiController; эти адреса
 * остаются, пока виджет v2 не выйдет целиком, а потом станут постоянными редиректами.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class WidgetPublicController {

    /** Заявок с одного IP в час: защита от спама, обычному гостю хватит с запасом. */
    private static final int REQUESTS_PER_HOUR = 10;
    private static final DateTimeFormatter HOLD_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final WidgetBookingService widgets;
    private final PublicRateLimiter rateLimiter;
    private final WidgetNotifier notifier;

    // --- Страницы

    @GetMapping("/book/{secret}")
    public String landing(@PathVariable String secret, Model model, HttpServletRequest request) {
        BookingWidget w = require(secret);
        String baseUrl = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        List<String> photos = photosOf(w);

        model.addAttribute("widget", w);
        model.addAttribute("baseUrl", baseUrl);
        model.addAttribute("currentUrl", baseUrl + "/book/" + w.getSecret());
        model.addAttribute("photos", photos);
        model.addAttribute("firstPhoto", photos.isEmpty() ? null : photos.get(0));
        model.addAttribute("ogDescription", ogDescription(w));
        model.addAttribute("checkin", w.getCheckinTime().toString());
        model.addAttribute("checkout", w.getCheckoutTime().toString());
        model.addAttribute("bodyClass", "book-page book-page--" + themeOf(w));
        return "pages/widget/book";
    }

    /**
     * Страница бронирования с новым виджетом — адрес, который хозяин даёт гостям.
     * Пока минимальная: превью для мессенджеров и встраивание во фрейм — следующая фаза.
     */
    @GetMapping("/b/{slug}")
    public String page(@PathVariable String slug, Model model) {
        BookingWidget w = widgets.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("widget", w);
        model.addAttribute("ogDescription", ogDescription(w));
        model.addAttribute("pageClass", "page page--" + themeOf(w));
        return "pages/widget/page";
    }

    @GetMapping("/widget/{secret}")
    public String frame(@PathVariable String secret, Model model, HttpServletRequest request,
                        HttpServletResponse response) {
        BookingWidget w = require(secret);
        // Встраивать можно только на наш домен и сайты, разрешённые хозяином
        response.setHeader("Content-Security-Policy", WidgetOrigins.frameAncestors(w.getAllowedOrigins()));
        model.addAttribute("widget", w);
        model.addAttribute("baseUrl",
                ServletUriComponentsBuilder.fromContextPath(request).build().toUriString());
        return "pages/widget/frame";
    }

    // --- JSON API

    /** Настройки виджета и занятые ночи на всё окно бронирования. */
    @GetMapping("/widget/{secret}/availability")
    @ResponseBody
    public Map<String, Object> availability(@PathVariable String secret) {
        BookingWidget w = require(secret);
        LocalDate today = LocalDate.now();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", w.getTitle());
        body.put("today", today.toString());
        body.put("maxDate", WidgetBookingService.maxDate(w, today).toString());
        body.put("minNights", w.getMinNights());
        body.put("maxNights", w.getMaxNights());
        body.put("maxGuests", w.getMaxGuests());
        body.put("showPrice", Boolean.TRUE.equals(w.getShowPrice()));
        body.put("showPoweredBy", Boolean.TRUE.equals(w.getShowPoweredBy()));
        body.put("theme", themeOf(w));
        body.put("checkinTime", w.getCheckinTime().toString());
        body.put("checkoutTime", w.getCheckoutTime().toString());
        body.put("holdHours", Math.max(1, (w.getHoldMinutes() + 59) / 60));
        body.put("busyDays", widgets.busyNights(w, today).stream().map(LocalDate::toString).toList());
        return body;
    }

    @GetMapping("/widget/{secret}/price")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> price(
            @PathVariable String secret,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer guests) {
        BookingWidget w = require(secret);
        String error = WidgetBookingService.validateStay(from, to, LocalDate.now(),
                w.getMinNights(), w.getMaxNights(), w.getBookingWindowDays());
        if (error != null) return ResponseEntity.badRequest().body(Map.of("message", error));

        WidgetPricing.Quote quote = widgets.quote(w, from, to, null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nights", quote.nights());
        // Хост скрыл цены или задал их не на все ночи — отдаём только число ночей
        boolean show = Boolean.TRUE.equals(w.getShowPrice()) && quote.complete();
        body.put("price", show ? quote.total() : null);
        List<Map<String, Object>> breakdown = new ArrayList<>();
        if (show) {
            for (WidgetPricing.NightPrice n : quote.breakdown()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", n.date().toString());
                row.put("price", n.price().setScale(0, RoundingMode.HALF_UP));
                breakdown.add(row);
            }
        }
        body.put("breakdown", breakdown);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/widget/{secret}/request")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> request(@PathVariable String secret,
                                                       @RequestBody RequestForm form,
                                                       HttpServletRequest http) {
        BookingWidget w = require(secret);
        String ip = http.getRemoteAddr();

        // Honeypot: поле скрыто от людей, заполняют его только боты. Отвечаем «успехом»,
        // чтобы бот не подбирал, что именно не так.
        if (form.website() != null && !form.website().isBlank()) {
            return ResponseEntity.ok(Map.of("status", "PENDING",
                    "message", "Заявка отправлена. Хозяин свяжется с вами."));
        }
        if (!rateLimiter.allow("widget-request:" + ip, REQUESTS_PER_HOUR, 3_600_000L)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "Слишком много заявок. Попробуйте позже.");
        }
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(form.from());
            to = LocalDate.parse(form.to());
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "Выберите даты заезда и выезда");
        }

        Submission result = widgets.submit(w, StayRequest.legacy(
                from, to, form.guests() == null ? 1 : form.guests(),
                form.name(), form.phone(), form.email(), form.note(),
                Boolean.TRUE.equals(form.consent())));
        if (!result.ok()) return error(HttpStatus.BAD_REQUEST, result.errorMessage());

        notifier.created(w, result.booking(), result.holdExpiresAt(),
                ServletUriComponentsBuilder.fromContextPath(http).build().toUriString());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.booking().getStatus());
        if (result.holdExpiresAt() == null) {
            body.put("message", "Бронь подтверждена. Хозяин свяжется с вами.");
        } else {
            body.put("holdExpiresAt", result.holdExpiresAt().toString());
            body.put("message", "Заявка отправлена. Даты за вами до "
                    + result.holdExpiresAt().format(HOLD_TIME) + " — хозяин свяжется с вами для подтверждения.");
        }
        return ResponseEntity.ok(body);
    }

    private BookingWidget require(String secret) {
        return widgets.findActive(secret)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }

    private static String themeOf(BookingWidget w) {
        String theme = w.getTheme();
        return "dark".equals(theme) || "auto".equals(theme) ? theme : "light";
    }

    static List<String> photosOf(BookingWidget w) {
        List<String> photos = new ArrayList<>();
        JsonNode json = w.getPhotosJson();
        if (json != null && json.isArray()) {
            for (JsonNode n : json) {
                if (n.isTextual() && n.asText().startsWith("https://")) photos.add(n.asText());
            }
        }
        return photos;
    }

    private static String ogDescription(BookingWidget w) {
        String d = w.getDescription();
        if (d == null || d.isBlank()) return "Бронирование напрямую у хозяина, без комиссии площадок.";
        String flat = d.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 197) + "…" : flat;
    }

    public record RequestForm(String from, String to, Integer guests,
                              String name, String phone, String email, String note,
                              Boolean consent, String captchaToken, String website) {}
}
