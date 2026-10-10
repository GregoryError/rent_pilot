package ru.rentoptima.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.WidgetPhoto;
import ru.rentoptima.payment.PaymentProvider;
import ru.rentoptima.service.PublicRateLimiter;
import ru.rentoptima.service.WidgetBookingService;
import ru.rentoptima.service.WidgetBookingService.PromoCheck;
import ru.rentoptima.service.WidgetBookingService.StayRequest;
import ru.rentoptima.service.WidgetBookingService.Submission;
import ru.rentoptima.service.WidgetCalendar;
import ru.rentoptima.service.WidgetError;
import ru.rentoptima.service.WidgetFormToken;
import ru.rentoptima.service.WidgetGuestCalendar;
import ru.rentoptima.service.WidgetLayout;
import ru.rentoptima.service.WidgetNotifier;
import ru.rentoptima.service.WidgetPhotoService;
import ru.rentoptima.service.WidgetPricing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Публичный API виджета бронирования v2, без авторизации. Ключ — адрес виджета (slug).
 * <ul>
 *   <li>{@code GET  config} — настройки и тексты, каркас виджета;</li>
 *   <li>{@code GET  availability} — дни: занятость, цена ночи, можно ли заехать и выехать;</li>
 *   <li>{@code GET  alternatives} — ближайшие свободные даты той же длины;</li>
 *   <li>{@code POST quote} — расчёт суммы, считает только сервер;</li>
 *   <li>{@code POST booking} — заявка или бронь;</li>
 *   <li>{@code GET  bookings/{requestId}/calendar.ics} — событие для календаря гостя.</li>
 * </ul>
 * GET-ответы кэшируются на 60 секунд и отдаются с ETag (WidgetWebConfig). Запросы с
 * чужих сайтов проходят, только если сайт есть в списке разрешённых у виджета
 * (WidgetCorsConfig). Сессии и cookie не участвуют, CSRF для /api/** выключен.
 * Ошибки — {@code {code, message}}: код для перевода на стороне виджета, message —
 * русский текст по умолчанию.
 */
@Slf4j
@RestController
@RequestMapping("/api/widget/{slug}")
@RequiredArgsConstructor
public class WidgetApiController {

    /** Заявок с одного IP в час: защита от спама, обычному гостю хватит с запасом. */
    static final int BOOKINGS_PER_HOUR = 10;
    private static final CacheControl CACHE = CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic();

    private final WidgetBookingService widgets;
    private final WidgetFormToken formToken;
    private final PublicRateLimiter rateLimiter;
    private final WidgetNotifier notifier;
    private final PaymentProvider payments;
    private final WidgetPhotoService photos;

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config(@PathVariable String slug, HttpServletRequest http) {
        BookingWidget w = require(slug);
        LocalDate today = LocalDate.now();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slug", w.getSlug());
        body.put("title", w.getTitle());
        body.put("mode", w.getMode());
        body.put("currency", "RUB");
        body.put("today", today.toString());
        body.put("maxDate", WidgetBookingService.maxDate(w, today).toString());
        body.put("minNights", w.getMinNights());
        body.put("maxNights", w.getMaxNights());
        body.put("maxGuests", w.getMaxGuests());
        body.put("petsAllowed", Boolean.TRUE.equals(w.getPetsAllowed()));
        body.put("checkinTime", w.getCheckinTime().toString());
        body.put("checkoutTime", w.getCheckoutTime().toString());
        body.put("holdMinutes", w.getHoldMinutes());
        body.put("showPrice", Boolean.TRUE.equals(w.getShowPrice()));
        body.put("showPoweredBy", Boolean.TRUE.equals(w.getShowPoweredBy()));
        body.put("theme", w.getTheme());
        body.put("weeklyDiscountPercent", w.getWeeklyDiscountPercent());
        body.put("monthlyDiscountPercent", w.getMonthlyDiscountPercent());
        body.put("prepaymentPercent", w.getPrepaymentPercent());
        body.put("description", w.getDescription());
        body.put("rules", w.getRules());
        body.put("cancellationPolicy", w.getCancellationPolicy());
        body.put("addressHint", w.getAddressHint());
        // Сначала загруженные фото (с вариантами и заглушкой), затем ссылки, заданные раньше
        String baseUrl = ServletUriComponentsBuilder.fromContextPath(http).build().toUriString();
        List<Map<String, Object>> gallery = new ArrayList<>();
        for (WidgetPhoto p : photos.list(w.getId())) gallery.add(photos.view(p, baseUrl));
        for (String url : WidgetPublicController.photosOf(w)) gallery.add(Map.of("src", url));
        body.put("photos", gallery);
        ObjectNode layout = WidgetLayout.normalize(w.getConfigJson());
        body.put("layout", layout);
        body.put("amenities", WidgetLayout.amenities(w.getAmenities()));
        body.put("mapUrl", WidgetLayout.mapUrl(w.getMapUrl()));
        // Контакты до брони отдаём, только если хозяин оставил блок «Контакты» видимым
        if (!WidgetLayout.hidden(layout, "contacts")) {
            Map<String, Object> contacts = new LinkedHashMap<>();
            contacts.put("phone", w.getContactPhone());
            contacts.put("telegram", w.getContactTelegram());
            contacts.put("whatsapp", w.getContactWhatsapp());
            body.put("contacts", contacts);
        }
        return ResponseEntity.ok().cacheControl(CACHE).body(body);
    }

    /**
     * Дни {@code [from, to)}; без параметров — всё окно бронирования. Виджет запрашивает
     * ближайшие месяцы сразу, дальние — когда гость до них долистает.
     */
    @GetMapping("/availability")
    public ResponseEntity<Map<String, Object>> availability(
            @PathVariable String slug,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        BookingWidget w = require(slug);
        LocalDate today = LocalDate.now();

        List<Map<String, Object>> days = new ArrayList<>();
        for (WidgetCalendar.Day d : widgets.days(w, from, to, today)) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("d", d.date().toString());
            if (d.price() != null) day.put("p", rub(d.price()));
            if (d.busy()) day.put("b", true);
            // "ok" не передаём: так ответ на полгода вдвое короче
            if (!WidgetCalendar.OK.equals(d.checkin()) && !d.busy()) day.put("ci", d.checkin());
            if (!WidgetCalendar.OK.equals(d.checkout())) day.put("co", d.checkout());
            days.add(day);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("today", today.toString());
        body.put("maxDate", WidgetBookingService.maxDate(w, today).toString());
        body.put("minNights", w.getMinNights());
        body.put("days", days);
        return ResponseEntity.ok().cacheControl(CACHE).body(body);
    }

    @GetMapping("/alternatives")
    public ResponseEntity<Map<String, Object>> alternatives(
            @PathVariable String slug,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkin,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkout) {
        BookingWidget w = require(slug);
        List<Map<String, String>> stays = new ArrayList<>();
        for (WidgetCalendar.Stay s : widgets.alternatives(w, checkin, checkout, LocalDate.now())) {
            stays.add(Map.of("checkin", s.checkin().toString(), "checkout", s.checkout().toString()));
        }
        return ResponseEntity.ok().cacheControl(CACHE).body(Map.of("alternatives", stays));
    }

    @PostMapping("/quote")
    public ResponseEntity<Map<String, Object>> quote(@PathVariable String slug,
                                                     @RequestBody QuoteForm form) {
        BookingWidget w = require(slug);
        LocalDate today = LocalDate.now();
        LocalDate checkin = date(form.checkin());
        LocalDate checkout = date(form.checkout());
        WidgetError error = WidgetCalendar.checkRules(checkin, checkout, WidgetBookingService.rules(w, today));
        if (error != null) return error(HttpStatus.BAD_REQUEST, error, w);

        PromoCheck promo = widgets.findPromo(w, form.promo(), today);
        Map<String, Object> body = quoteBody(w, widgets.quote(w, checkin, checkout, promo.promo()));
        // Неверный промокод расчёту не мешает: гость видит сумму без него и причину
        if (promo.error() != null) {
            body.put("promoError", Map.of("code", promo.error().name(), "message", promo.error().message()));
        }
        body.put("formToken", formToken.issue(w.getId()));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/booking")
    public ResponseEntity<Map<String, Object>> booking(@PathVariable String slug,
                                                       @RequestBody BookingForm form,
                                                       HttpServletRequest http) {
        BookingWidget w = require(slug);
        String ip = http.getRemoteAddr();

        // Honeypot: поле скрыто от людей, заполняют его только боты. Отвечаем «успехом»,
        // чтобы бот не подбирал, что именно не так.
        if (form.website() != null && !form.website().isBlank()) {
            Map<String, Object> fake = new LinkedHashMap<>();
            fake.put("status", WidgetBookingService.STATUS_PENDING);
            return ResponseEntity.ok(fake);
        }
        switch (formToken.check(w.getId(), form.formToken())) {
            case TOO_FAST -> {
                return error(HttpStatus.BAD_REQUEST, WidgetError.TOO_FAST, w);
            }
            case EXPIRED, INVALID -> {
                return error(HttpStatus.BAD_REQUEST, WidgetError.FORM_EXPIRED, w);
            }
            default -> { }
        }
        if (form.name() == null || form.name().isBlank()) {
            return error(HttpStatus.BAD_REQUEST, WidgetError.NAME, w);
        }

        // Лимит считает только правдоподобные заявки: виджет сам повторяет отправку после
        // TOO_FAST (автозаполнение формы), и эта попытка не должна съедать лимит гостя.
        if (!rateLimiter.allow("widget-request:" + ip, BOOKINGS_PER_HOUR, 3_600_000L)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, WidgetError.RATE_LIMIT, w);
        }

        Utm utm = form.utm() == null ? new Utm(null, null, null) : form.utm();
        Submission result = widgets.submit(w, new StayRequest(
                date(form.checkin()), date(form.checkout()),
                form.adults() == null ? 1 : form.adults(),
                form.children() == null ? 0 : form.children(),
                form.pets() == null ? 0 : form.pets(),
                form.name(), form.phone(), form.email(), form.note(),
                Boolean.TRUE.equals(form.consent()), form.promo(), form.expectedTotal(),
                form.locale(), utm.source(), utm.medium(), utm.campaign(), form.referrer()));
        if (!result.ok()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", result.error().name());
            body.put("message", result.errorMessage());
            if (result.quote() != null) body.put("quote", quoteBody(w, result.quote()));
            HttpStatus status = result.error() == WidgetError.DATES_TAKEN
                    || result.error() == WidgetError.PRICE_CHANGED ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).body(body);
        }

        Booking b = result.booking();
        String baseUrl = ServletUriComponentsBuilder.fromContextPath(http).build().toUriString();
        notifier.created(w, b, result.holdExpiresAt(), baseUrl);
        PaymentProvider.PaymentStart payment = payments.start(b,
                b.getPrepaymentAmount() == null ? BigDecimal.ZERO : b.getPrepaymentAmount());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", b.getStatus());
        body.put("number", b.getPublicCode());
        body.put("requestId", b.getExternalId());
        body.put("checkin", b.getCheckIn().toString());
        body.put("checkout", b.getCheckOut().toString());
        body.put("checkinTime", w.getCheckinTime().toString());
        body.put("checkoutTime", w.getCheckoutTime().toString());
        if (result.holdExpiresAt() != null) body.put("holdExpiresAt", result.holdExpiresAt().toString());
        body.put("quote", quoteBody(w, result.quote()));
        body.put("icsUrl", baseUrl + "/api/widget/" + w.getSlug() + "/bookings/" + b.getExternalId() + "/calendar.ics");
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("kind", payment.kind());
        pay.put("redirectUrl", payment.redirectUrl());
        body.put("payment", pay);
        Map<String, Object> contacts = new LinkedHashMap<>();
        contacts.put("phone", w.getContactPhone());
        contacts.put("telegram", w.getContactTelegram());
        contacts.put("whatsapp", w.getContactWhatsapp());
        body.put("contacts", contacts);
        body.put("rules", w.getRules());
        return ResponseEntity.ok(body);
    }

    /** Событие для календаря гостя. Без персональных данных: название, даты, время заезда и выезда. */
    @GetMapping("/bookings/{requestId}/calendar.ics")
    public ResponseEntity<byte[]> calendar(@PathVariable String slug, @PathVariable String requestId) {
        BookingWidget w = require(slug);
        Booking b = widgets.findByRequestId(w, requestId)
                .filter(x -> WidgetBookingService.STATUS_PENDING.equals(x.getStatus())
                        || WidgetBookingService.STATUS_BOOKED.equals(x.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String ics = WidgetGuestCalendar.write(w, b);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar; charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"booking-" + b.getPublicCode() + ".ics\"")
                .cacheControl(CacheControl.noStore())
                .body(ics.getBytes(StandardCharsets.UTF_8));
    }

    /** Расчёт для гостя. Хозяин скрыл цены или задал их не на все ночи — только число ночей. */
    static Map<String, Object> quoteBody(BookingWidget w, WidgetPricing.Quote q) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nights", q.nights());
        boolean show = Boolean.TRUE.equals(w.getShowPrice()) && q.complete();
        body.put("priceOnRequest", !show);
        if (!show) return body;

        List<Map<String, Object>> nightly = new ArrayList<>();
        for (WidgetPricing.NightPrice n : q.breakdown()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", n.date().toString());
            row.put("price", rub(n.price()));
            nightly.add(row);
        }
        body.put("nightly", nightly);
        body.put("accommodation", q.accommodation());
        if (q.lengthDiscount().signum() > 0) {
            body.put("lengthDiscount", Map.of("percent", q.lengthPercent(), "amount", q.lengthDiscount()));
        }
        if (q.promoCode() != null) {
            body.put("promo", Map.of("code", q.promoCode(), "amount", q.promoDiscount()));
        }
        body.put("cleaningFee", q.cleaningFee());
        body.put("total", q.total());
        if (q.prepayment().signum() > 0) {
            body.put("prepayment", Map.of("percent", q.prepaymentPercent(), "amount", q.prepayment()));
        }
        body.put("currency", "RUB");
        return body;
    }

    private BookingWidget require(String slug) {
        return widgets.findBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, WidgetError error, BookingWidget w) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.name());
        body.put("message", WidgetBookingService.message(error, w));
        return ResponseEntity.status(status).body(body);
    }

    private static LocalDate date(String iso) {
        try {
            return iso == null ? null : LocalDate.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal rub(BigDecimal v) {
        return v.setScale(0, java.math.RoundingMode.HALF_UP);
    }

    public record QuoteForm(String checkin, String checkout, String promo) {}

    public record Utm(String source, String medium, String campaign) {}

    public record BookingForm(String checkin, String checkout, Integer adults, Integer children, Integer pets,
                              String name, String phone, String email, String note, String promo,
                              BigDecimal expectedTotal, Boolean consent, String formToken, String website,
                              String locale, Utm utm, String referrer) {}
}
