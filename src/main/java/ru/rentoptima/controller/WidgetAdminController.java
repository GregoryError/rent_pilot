package ru.rentoptima.controller;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.PromoCode;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.PromoCodeRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.config.WidgetCorsConfig;
import ru.rentoptima.service.WidgetCalendar;
import ru.rentoptima.service.WidgetOrigins;
import ru.rentoptima.service.WidgetSlug;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Настройка страниц / виджетов бронирования (блок 4.9).
 * Создание виджета заводит и его канал типа WIDGET — отдельно каналом управлять не нужно.
 */
@Controller
@RequestMapping("/settings/widgets")
@RequiredArgsConstructor
public class WidgetAdminController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_PHOTOS = 10;

    private final BookingWidgetRepository widgetRepo;
    private final ChannelRepository channelRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final PropertyRepository propertyRepo;
    private final PromoCodeRepository promoRepo;
    private final WidgetCorsConfig cors;

    private static final String[] WEEKDAYS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @GetMapping
    public String list(Model model, HttpServletRequest request) {
        Long tenantId = AuthContext.tenantId();
        Map<Long, String> unitLabels = unitLabels(tenantId);
        String baseUrl = baseUrl(request);

        List<WidgetRow> rows = new ArrayList<>();
        for (BookingWidget w : widgetRepo.findByTenantIdAndActiveTrueOrderByCreatedAtAsc(tenantId)) {
            rows.add(new WidgetRow(w.getId(), w.getTitle(),
                    unitLabels.getOrDefault(w.getUnitTypeId(), "категория удалена"),
                    baseUrl + "/book/" + w.getSecret(), "widget-link-" + w.getId()));
        }
        List<UnitOption> options = new ArrayList<>();
        unitLabels.forEach((id, label) -> options.add(new UnitOption(id, label)));

        model.addAttribute("activePage", "widgets");
        model.addAttribute("widgets", rows);
        model.addAttribute("unitTypes", options);
        return "pages/settings/widgets";
    }

    @PostMapping("/create")
    @Transactional
    public String create(@RequestParam String title,
                         @RequestParam Long unitTypeId,
                         RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        UnitType ut = unitTypeRepo.findById(unitTypeId).orElse(null);
        if (ut == null || !tenantId.equals(ut.getTenantId())) {
            redirect.addFlashAttribute("error", "Категория не найдена");
            return "redirect:/settings/widgets";
        }
        String cleanTitle = clean(title, 255);
        if (cleanTitle == null) {
            redirect.addFlashAttribute("error", "Укажите название");
            return "redirect:/settings/widgets";
        }

        Channel channel = new Channel();
        channel.setTenantId(tenantId);
        channel.setUnitTypeId(unitTypeId);
        channel.setChannelType(Channel.ChannelType.WIDGET);
        channel.setColor(ru.rentoptima.service.ChannelPalette.randomColor(
                channelRepo.findByTenantIdAndActiveTrue(tenantId).stream().map(Channel::getColor).toList()));
        channel.setName("Прямая бронь: " + (cleanTitle.length() > 180 ? cleanTitle.substring(0, 180) : cleanTitle));
        channel.setConfigJson(JsonNodeFactory.instance.objectNode());
        // Тянуть каналу нечего — планировщик синхронизации его не трогает
        channel.setSyncEnabled(false);
        channelRepo.save(channel);

        BookingWidget w = new BookingWidget();
        w.setTenantId(tenantId);
        w.setChannelId(channel.getId());
        w.setUnitTypeId(unitTypeId);
        w.setSecret(generateSecret());
        w.setSlug(WidgetSlug.unique(WidgetSlug.fromTitle(cleanTitle), widgetRepo::existsBySlug));
        w.setTitle(cleanTitle);
        widgetRepo.save(w);

        redirect.addFlashAttribute("success", "Страница бронирования создана");
        return "redirect:/settings/widgets/" + w.getId();
    }

    @GetMapping("/{id}")
    public String view(@PathVariable Long id, Model model, HttpServletRequest request,
                       RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        BookingWidget w = widgetRepo.findByIdAndTenantIdAndActiveTrue(id, tenantId).orElse(null);
        if (w == null) {
            redirect.addFlashAttribute("error", "Страница бронирования не найдена");
            return "redirect:/settings/widgets";
        }
        String baseUrl = baseUrl(request);
        String secret = w.getSecret();

        model.addAttribute("activePage", "widgets");
        model.addAttribute("w", w);
        model.addAttribute("unitLabel", unitLabels(tenantId).getOrDefault(w.getUnitTypeId(), ""));
        model.addAttribute("photosText", String.join("\n", WidgetPublicController.photosOf(w)));
        model.addAttribute("checkin", w.getCheckinTime().toString());
        model.addAttribute("checkout", w.getCheckoutTime().toString());
        model.addAttribute("directLink", baseUrl + "/book/" + secret);
        model.addAttribute("iframeCode", "<iframe src=\"" + baseUrl + "/widget/" + secret
                + "\" width=\"100%\" height=\"760\" frameborder=\"0\"></iframe>");
        model.addAttribute("jsCode", "<div id=\"optirent-widget\" data-secret=\"" + secret + "\"></div>\n"
                + "<script src=\"" + baseUrl + "/widget.js\" async></script>");
        model.addAttribute("holdHours", Math.max(1, (w.getHoldMinutes() + 59) / 60));
        model.addAttribute("cleaningFee", w.getCleaningFee().setScale(0, RoundingMode.HALF_UP).toPlainString());
        model.addAttribute("noCheckinDays", dayOptions(w.getNoCheckinDays()));
        model.addAttribute("noCheckoutDays", dayOptions(w.getNoCheckoutDays()));
        model.addAttribute("publicAddress", baseUrl + "/b/");
        List<PromoRow> promos = new ArrayList<>();
        for (PromoCode p : promoRepo.findByWidgetIdAndTenantIdOrderByCreatedAtDesc(w.getId(), tenantId)) {
            promos.add(promoRow(p));
        }
        model.addAttribute("promos", promos);
        return "pages/settings/widget-edit";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String title,
                         @RequestParam Integer minNights,
                         @RequestParam Integer maxNights,
                         @RequestParam Integer maxGuests,
                         @RequestParam Integer bookingWindowDays,
                         @RequestParam Integer holdHours,
                         @RequestParam String checkinTime,
                         @RequestParam String checkoutTime,
                         @RequestParam(required = false, defaultValue = "light") String theme,
                         @RequestParam(required = false, defaultValue = "false") boolean showPrice,
                         @RequestParam(required = false, defaultValue = "false") boolean showPoweredBy,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) String rules,
                         @RequestParam(required = false) String cancellationPolicy,
                         @RequestParam(required = false) String addressHint,
                         @RequestParam(required = false) String photos,
                         @RequestParam(required = false) String slug,
                         @RequestParam(required = false, defaultValue = "REQUEST") String mode,
                         @RequestParam(required = false) BigDecimal cleaningFee,
                         @RequestParam(required = false) Integer weeklyDiscountPercent,
                         @RequestParam(required = false) Integer monthlyDiscountPercent,
                         @RequestParam(required = false) Integer prepaymentPercent,
                         @RequestParam(required = false, defaultValue = "false") boolean petsAllowed,
                         @RequestParam(required = false) List<Integer> noCheckinDays,
                         @RequestParam(required = false) List<Integer> noCheckoutDays,
                         @RequestParam(required = false) String allowedOrigins,
                         @RequestParam(required = false) String contactPhone,
                         @RequestParam(required = false) String contactTelegram,
                         @RequestParam(required = false) String contactWhatsapp,
                         RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        BookingWidget w = widgetRepo.findByIdAndTenantIdAndActiveTrue(id, tenantId).orElse(null);
        if (w == null) {
            redirect.addFlashAttribute("error", "Страница бронирования не найдена");
            return "redirect:/settings/widgets";
        }
        String cleanTitle = clean(title, 255);
        LocalTime in;
        LocalTime out;
        try {
            in = LocalTime.parse(checkinTime);
            out = LocalTime.parse(checkoutTime);
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "Время заезда и выезда — в формате ЧЧ:ММ");
            return "redirect:/settings/widgets/" + id;
        }
        if (cleanTitle == null) {
            redirect.addFlashAttribute("error", "Укажите название");
            return "redirect:/settings/widgets/" + id;
        }

        w.setTitle(cleanTitle);
        w.setMinNights(clamp(minNights, 1, 365));
        w.setMaxNights(Math.max(w.getMinNights(), clamp(maxNights, 1, 365)));
        w.setMaxGuests(clamp(maxGuests, 1, 50));
        w.setBookingWindowDays(clamp(bookingWindowDays, 7, 540));
        w.setHoldMinutes(clamp(holdHours, 1, 168) * 60);
        w.setMode(BookingWidget.MODE_INSTANT.equals(mode) ? BookingWidget.MODE_INSTANT : BookingWidget.MODE_REQUEST);
        w.setCleaningFee(cleaningFee == null || cleaningFee.signum() < 0 ? BigDecimal.ZERO
                : cleaningFee.min(BigDecimal.valueOf(1_000_000)).setScale(2, RoundingMode.HALF_UP));
        w.setWeeklyDiscountPercent(clamp(weeklyDiscountPercent, 0, 90));
        w.setMonthlyDiscountPercent(clamp(monthlyDiscountPercent, 0, 90));
        w.setPrepaymentPercent(clamp(prepaymentPercent, 0, 100));
        w.setPetsAllowed(petsAllowed);
        w.setNoCheckinDays(WidgetCalendar.formatDays(noCheckinDays));
        w.setNoCheckoutDays(WidgetCalendar.formatDays(noCheckoutDays));
        w.setAllowedOrigins(WidgetOrigins.normalize(allowedOrigins));
        w.setContactPhone(clean(contactPhone, 40));
        w.setContactTelegram(clean(contactTelegram, 80));
        w.setContactWhatsapp(clean(contactWhatsapp, 40));
        w.setCheckinTime(in);
        w.setCheckoutTime(out);
        w.setTheme("dark".equals(theme) || "auto".equals(theme) ? theme : "light");
        w.setShowPrice(showPrice);
        w.setShowPoweredBy(showPoweredBy);
        w.setDescription(clean(description, 5000));
        w.setRules(clean(rules, 5000));
        w.setCancellationPolicy(clean(cancellationPolicy, 5000));
        w.setAddressHint(clean(addressHint, 255));
        w.setPhotosJson(parsePhotos(photos));
        w.setUpdatedAt(LocalDateTime.now());

        // Адрес меняем последним: если он занят или с ошибкой, остальное всё равно сохраняется
        String slugError = null;
        String newSlug = slug == null ? "" : slug.trim().toLowerCase(java.util.Locale.ROOT);
        if (!newSlug.isEmpty() && !newSlug.equals(w.getSlug())) {
            if (!WidgetSlug.valid(newSlug)) {
                slugError = "Адрес страницы — от " + WidgetSlug.MIN + " до " + WidgetSlug.MAX
                        + " символов: латинские буквы, цифры и дефис";
            } else if (widgetRepo.existsBySlug(newSlug)) {
                slugError = "Адрес «" + newSlug + "» уже занят";
            } else {
                w.setSlug(newSlug);
            }
        }
        widgetRepo.save(w);
        cors.evict();

        if (slugError != null) {
            redirect.addFlashAttribute("error", "Сохранено всё, кроме адреса страницы. " + slugError);
        } else {
            redirect.addFlashAttribute("success", "Сохранено");
        }
        return "redirect:/settings/widgets/" + id;
    }

    // --- Промокоды

    @PostMapping("/{id}/promo")
    public String createPromo(@PathVariable Long id,
                              @RequestParam String code,
                              @RequestParam String discountType,
                              @RequestParam BigDecimal discountValue,
                              @RequestParam(required = false) String validUntil,
                              @RequestParam(required = false) Integer maxUses,
                              RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        BookingWidget w = widgetRepo.findByIdAndTenantIdAndActiveTrue(id, tenantId).orElse(null);
        if (w == null) return "redirect:/settings/widgets";

        String normalized = PromoCode.normalize(code);
        boolean percent = PromoCode.TYPE_PERCENT.equals(discountType);
        String error = null;
        LocalDate until = null;
        if (normalized == null || normalized.length() > 40 || !normalized.matches("[A-ZА-ЯЁ0-9_-]+")) {
            error = "Код — буквы, цифры, дефис и подчёркивание, до 40 символов";
        } else if (discountValue == null || discountValue.signum() <= 0
                || (percent && discountValue.compareTo(BigDecimal.valueOf(100)) > 0)) {
            error = percent ? "Скидка — от 1 до 100 %" : "Укажите сумму скидки";
        } else if (promoRepo.existsByWidgetIdAndCode(w.getId(), normalized)) {
            error = "Промокод «" + normalized + "» уже есть";
        } else if (validUntil != null && !validUntil.isBlank()) {
            try {
                until = LocalDate.parse(validUntil);
            } catch (Exception e) {
                error = "Дата окончания — в формате ГГГГ-ММ-ДД";
            }
        }
        if (error != null) {
            redirect.addFlashAttribute("error", error);
            return "redirect:/settings/widgets/" + id;
        }

        PromoCode p = new PromoCode();
        p.setTenantId(tenantId);
        p.setWidgetId(w.getId());
        p.setCode(normalized);
        p.setDiscountType(percent ? PromoCode.TYPE_PERCENT : PromoCode.TYPE_AMOUNT);
        p.setDiscountValue(discountValue.setScale(percent ? 0 : 2, RoundingMode.HALF_UP));
        p.setValidUntil(until);
        p.setMaxUses(maxUses == null || maxUses < 1 ? null : maxUses);
        promoRepo.save(p);
        redirect.addFlashAttribute("success", "Промокод " + normalized + " создан");
        return "redirect:/settings/widgets/" + id;
    }

    /** Выключает или снова включает промокод. Не удаляем: на него ссылаются брони. */
    @PostMapping("/{id}/promo/{promoId}/toggle")
    public String togglePromo(@PathVariable Long id, @PathVariable Long promoId,
                              RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        PromoCode p = promoRepo.findByIdAndTenantId(promoId, tenantId)
                .filter(x -> x.getWidgetId().equals(id)).orElse(null);
        if (p == null) {
            redirect.addFlashAttribute("error", "Промокод не найден");
            return "redirect:/settings/widgets/" + id;
        }
        p.setActive(!Boolean.TRUE.equals(p.getActive()));
        promoRepo.save(p);
        redirect.addFlashAttribute("success", Boolean.TRUE.equals(p.getActive())
                ? "Промокод снова действует" : "Промокод выключен");
        return "redirect:/settings/widgets/" + id;
    }

    private static PromoRow promoRow(PromoCode p) {
        boolean percent = PromoCode.TYPE_PERCENT.equals(p.getDiscountType());
        String discount = p.getDiscountValue().setScale(0, RoundingMode.HALF_UP).toPlainString()
                + (percent ? " %" : " ₽");
        String uses = p.getUsedCount() + (p.getMaxUses() == null ? "" : " из " + p.getMaxUses());
        boolean active = Boolean.TRUE.equals(p.getActive());
        boolean expired = p.getValidUntil() != null && LocalDate.now().isAfter(p.getValidUntil());
        boolean exhausted = p.getMaxUses() != null && p.getUsedCount() >= p.getMaxUses();
        String status = !active ? "Выключен" : expired ? "Срок истёк" : exhausted ? "Исчерпан" : "Действует";
        return new PromoRow(p.getId(), p.getCode(), discount,
                p.getValidUntil() == null ? "бессрочно" : "до " + p.getValidUntil().format(DAY),
                uses, status,
                active && !expired && !exhausted ? "tag tag--green" : "tag tag--muted",
                active ? "Выключить" : "Включить");
    }

    private static List<DayOption> dayOptions(String stored) {
        Set<DayOfWeek> chosen = WidgetCalendar.parseDays(stored);
        List<DayOption> options = new ArrayList<>();
        for (int n = 1; n <= 7; n++) {
            options.add(new DayOption(n, WEEKDAYS[n - 1], chosen.contains(DayOfWeek.of(n))));
        }
        return options;
    }

    @PostMapping("/{id}/regenerate")
    public String regenerate(@PathVariable Long id, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        BookingWidget w = widgetRepo.findByIdAndTenantIdAndActiveTrue(id, tenantId).orElse(null);
        if (w == null) return "redirect:/settings/widgets";
        w.setSecret(generateSecret());
        w.setUpdatedAt(LocalDateTime.now());
        widgetRepo.save(w);
        cors.evict();
        redirect.addFlashAttribute("success", "Ссылка перевыпущена. Старые ссылки и код на сайте больше не работают.");
        return "redirect:/settings/widgets/" + id;
    }

    /** Мягкое удаление: страница перестаёт открываться, заявки и брони остаются. */
    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        BookingWidget w = widgetRepo.findByIdAndTenantIdAndActiveTrue(id, tenantId).orElse(null);
        if (w == null) return "redirect:/settings/widgets";
        w.setActive(false);
        w.setUpdatedAt(LocalDateTime.now());
        widgetRepo.save(w);
        redirect.addFlashAttribute("success", "Страница бронирования удалена");
        return "redirect:/settings/widgets";
    }

    private Map<Long, String> unitLabels(Long tenantId) {
        Map<Long, String> propertyNames = new HashMap<>();
        for (Property p : propertyRepo.findByTenantIdAndActiveTrue(tenantId)) {
            propertyNames.put(p.getId(), p.getName());
        }
        Map<Long, String> labels = new java.util.LinkedHashMap<>();
        for (UnitType ut : unitTypeRepo.findByTenantIdAndActiveTrue(tenantId)) {
            String property = propertyNames.get(ut.getPropertyId());
            if (property == null) continue;
            labels.put(ut.getId(), property + " / " + ut.getName());
        }
        return labels;
    }

    /** Только https-ссылки, по одной в строке: http-картинку браузер на https-странице не покажет. */
    static ArrayNode parsePhotos(String text) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        if (text == null) return array;
        for (String line : text.split("\\R")) {
            String url = line.trim();
            if (url.isEmpty() || url.length() > 1000) continue;
            if (!url.startsWith("https://") || url.matches(".*[\\s\"'<>].*")) continue;
            array.add(url);
            if (array.size() >= MAX_PHOTOS) break;
        }
        return array;
    }

    private static String baseUrl(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
    }

    private static String clean(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static int clamp(Integer value, int min, int max) {
        if (value == null) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static String generateSecret() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public record WidgetRow(Long id, String title, String unitLabel, String link, String inputId) {}

    public record UnitOption(Long id, String label) {}

    public record DayOption(int value, String label, boolean checked) {}

    public record PromoRow(Long id, String code, String discount, String validity, String uses,
                           String status, String statusCss, String toggleLabel) {}
}
