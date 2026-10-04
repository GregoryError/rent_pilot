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
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.security.AuthContext;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        w.setHoldHours(clamp(holdHours, 1, 168));
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
        widgetRepo.save(w);

        redirect.addFlashAttribute("success", "Сохранено");
        return "redirect:/settings/widgets/" + id;
    }

    @PostMapping("/{id}/regenerate")
    public String regenerate(@PathVariable Long id, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        BookingWidget w = widgetRepo.findByIdAndTenantIdAndActiveTrue(id, tenantId).orElse(null);
        if (w == null) return "redirect:/settings/widgets";
        w.setSecret(generateSecret());
        w.setUpdatedAt(LocalDateTime.now());
        widgetRepo.save(w);
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
}
