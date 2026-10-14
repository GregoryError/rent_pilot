package ru.rentoptima.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.WidgetCard;
import ru.rentoptima.entity.WidgetQrLink;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.security.Tenancy;
import ru.rentoptima.service.QrSvg;
import ru.rentoptima.service.WidgetCardService;
import ru.rentoptima.service.WidgetCardService.CardForm;
import ru.rentoptima.service.WidgetLayout;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Печатная карточка для квартиры (фаза 8): настройка с живым превью и страница печати.
 * <p>
 * Карточку рисует скрипт {@code static/js/widget-card.js} по данным
 * {@link WidgetCardService#data}: один и тот же код — и превью на странице настроек,
 * и лист на странице печати. PDF на сервере не делается: печатает браузер.
 */
@Controller
@RequestMapping("/settings/widgets/{id}/card")
@RequiredArgsConstructor
public class WidgetCardController {

    private final BookingWidgetRepository widgetRepo;
    private final WidgetCardService cards;
    private final ObjectMapper json = new ObjectMapper();

    @GetMapping
    public String page(@PathVariable Long id, Model model, HttpServletRequest request, RedirectAttributes redirect) {
        BookingWidget w = widget(id);
        fill(model, w, request);
        model.addAttribute("activePage", "widgets");
        model.addAttribute("defaultUntil", LocalDate.now().plusMonths(12).toString());
        return "pages/settings/widget-card";
    }

    /** Страница печати: только лист с карточками, без оформления админки. */
    @GetMapping("/print")
    public String print(@PathVariable Long id, Model model, HttpServletRequest request, RedirectAttributes redirect) {
        BookingWidget w = widget(id);
        fill(model, w, request);
        return "pages/settings/widget-card-print";
    }

    private void fill(Model model, BookingWidget w, HttpServletRequest request) {
        String baseUrl = baseUrl(request);
        String font = WidgetLayout.normalize(w.getConfigJson()).path("theme").path("font").asText();
        model.addAttribute("w", w);
        // «<» экранируется: тексты хозяина не должны закрыть тег script
        model.addAttribute("cardData", cards.data(w, baseUrl).toString().replace("<", "\\u003c"));
        model.addAttribute("fontCss", WidgetCardService.fontCss(font, baseUrl));
        model.addAttribute("fontFiles", WidgetCardService.fontFiles(font, baseUrl));
    }

    @PostMapping
    public String save(@PathVariable Long id,
                       @RequestParam(defaultValue = "A6") String format,
                       @RequestParam(defaultValue = "A4") String paper,
                       @RequestParam(defaultValue = "ru") String lang,
                       @RequestParam(defaultValue = "false") boolean mono,
                       @RequestParam(defaultValue = "false") boolean showContacts,
                       @RequestParam(defaultValue = "false") boolean showFeedback,
                       @RequestParam(required = false) Long promoCodeId,
                       @RequestParam(required = false) Long photoId,
                       @RequestParam(required = false) String wifiSsid,
                       @RequestParam(required = false) String wifiPassword,
                       @RequestParam(required = false) String texts,
                       @RequestParam(defaultValue = "false") boolean print,
                       RedirectAttributes redirect) {
        BookingWidget w = widget(id);
        JsonNode textsNode = JsonNodeFactory.instance.objectNode();
        try {
            if (texts != null && texts.length() <= 10_000) textsNode = json.readTree(texts);
        } catch (Exception e) {
            // тексты останутся по умолчанию
        }
        cards.save(w, new CardForm(format, paper, lang, mono, showContacts, showFeedback,
                promoCodeId, photoId, wifiSsid, wifiPassword, textsNode));
        if (print) return "redirect:/settings/widgets/" + id + "/card/print?auto=1";
        redirect.addFlashAttribute("success", "Карточка сохранена");
        return "redirect:/settings/widgets/" + id + "/card";
    }

    /** Промокод на повторный визит — создаётся и сразу выбирается для карточки. */
    @PostMapping("/promo")
    public String createPromo(@PathVariable Long id,
                              @RequestParam(required = false) String code,
                              @RequestParam(defaultValue = "PERCENT") String discountType,
                              @RequestParam BigDecimal discountValue,
                              @RequestParam(required = false) String validUntil,
                              @RequestParam(required = false) Integer maxUses,
                              RedirectAttributes redirect) {
        BookingWidget w = widget(id);
        try {
            LocalDate until = validUntil == null || validUntil.isBlank() ? null : LocalDate.parse(validUntil);
            var promo = cards.createPromo(w, code, "PERCENT".equals(discountType), discountValue, until, maxUses);
            redirect.addFlashAttribute("success", "Промокод " + promo.getCode() + " создан и выбран для карточки");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (java.time.format.DateTimeParseException e) {
            redirect.addFlashAttribute("error", "Дата окончания — в формате ГГГГ-ММ-ДД");
        }
        return "redirect:/settings/widgets/" + id + "/card";
    }

    @PostMapping("/regenerate")
    public String regenerate(@PathVariable Long id, RedirectAttributes redirect) {
        BookingWidget w = widget(id);
        cards.regenerate(w);
        redirect.addFlashAttribute("success", "Ссылка перевыпущена. Уже напечатанные карточки открывают страницу"
                + " бронирования, но промокод больше не подставляют — напечатайте новые");
        return "redirect:/settings/widgets/" + id + "/card";
    }

    /**
     * QR подключения к Wi-Fi для превью, пока хозяин набирает имя сети и пароль. POST —
     * чтобы пароль не попадал в адрес и журналы сервера.
     */
    @PostMapping(value = "/wifi-qr", produces = "image/svg+xml")
    @ResponseBody
    public ResponseEntity<String> wifiQr(@PathVariable Long id,
                                         @RequestParam String ssid,
                                         @RequestParam(required = false) String password) {
        widget(id);
        if (ssid.isBlank() || ssid.length() > 32 || (password != null && password.length() > 63)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(QrSvg.svg(QrSvg.wifi(ssid.trim(), password), "QR-код: подключение к Wi-Fi"));
    }

    /** QR отдельным файлом — если хозяин хочет вставить его в свой макет. */
    @GetMapping("/qr.svg")
    public ResponseEntity<byte[]> download(@PathVariable Long id,
                                           @RequestParam(defaultValue = "card") String kind,
                                           HttpServletRequest request) {
        BookingWidget w = widget(id);
        String baseUrl = baseUrl(request);
        String svg;
        if ("wifi".equals(kind)) {
            WidgetCard card = cards.card(w);
            if (card.getWifiSsid() == null) return ResponseEntity.notFound().build();
            svg = QrSvg.svg(QrSvg.wifi(card.getWifiSsid(), cards.wifiPassword(card)), "QR-код: подключение к Wi-Fi");
        } else if ("feedback".equals(kind)) {
            svg = QrSvg.svg(baseUrl + "/f/" + cards.link(w, WidgetQrLink.KIND_FEEDBACK).getCode(), "QR-код: обратная связь");
        } else {
            kind = "card";
            svg = QrSvg.svg(baseUrl + "/q/" + cards.link(w, WidgetQrLink.KIND_CARD).getCode(), "QR-код: страница бронирования");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/svg+xml"))
                .header("Content-Disposition", "attachment; filename=\"qr-" + kind + "-" + w.getSlug() + ".svg\"")
                .body(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + svg).getBytes(StandardCharsets.UTF_8));
    }

    /** Страница бронирования своего тенанта; чужая, удалённая или несуществующая — 404. */
    private BookingWidget widget(Long id) {
        return Tenancy.found(widgetRepo.findByIdAndTenantIdAndActiveTrue(id, AuthContext.tenantId()));
    }

    private static String baseUrl(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
    }
}
