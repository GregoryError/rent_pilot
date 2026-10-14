package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.security.Tenancy;
import ru.rentoptima.service.CompetitorService;
import ru.rentoptima.service.SettingsService;



import ru.rentoptima.service.PricingEngine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@RequestMapping("/pricing")
@RequiredArgsConstructor
@Controller
public class PricingController {

    private final CompetitorService competitorService;
    private final PropertyRepository propertyRepo;
    private final SettingsService settings;
    private final PricingEngine pricingEngine;

    @GetMapping
    public String pricing(Model model) {
        Long tenantId = AuthContext.tenantId();
        var properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        model.addAttribute("activePage", "pricing");
        // Квартиры нет — ни цен, ни рекомендаций: пустое состояние с кнопкой в мастер
        model.addAttribute("hasProperties", !properties.isEmpty());
        if (properties.isEmpty()) return "pages/pricing/index";

        var settingsMap = settings.getSettingsMap(tenantId);
        var listings = competitorService.getListings(tenantId);
        var latestPrices = competitorService.getLatestPrices(tenantId);
        var avgPrice = competitorService.getAverageCompetitorPrice(tenantId);

        // Рекомендации — по первому объекту; занятые дни не показываем: менять там нечего
        Property property = properties.get(0);
        List<PricingEngine.PricingRecommendation> recommendations = pricingEngine
                .getRecommendations(tenantId, property.getId()).stream()
                .filter(r -> r.status() != PricingEngine.DayStatus.BOOKED).toList();

        // Цена — квартиры, по которой считаются рекомендации (не настройки тенанта)
        UnitType unit = pricingEngine.pricedUnit(property.getId()).orElse(null);
        BigDecimal weekday = unit == null ? null : unit.getBasePrice();
        BigDecimal weekend = unit == null ? null : unit.getWeekendPrice();
        model.addAttribute("weekdayPrice", rub(weekday));
        model.addAttribute("weekendPrice", weekend != null ? rub(weekend) : weekday != null ? "как в будни" : "—");
        model.addAttribute("needsPrice", unit != null && weekday == null);
        model.addAttribute("severalUnits", pricingEngine.severalUnits(property.getId()));
        model.addAttribute("pricedUnitName", unit == null ? null : unit.getName());
        model.addAttribute("boundsWarning", boundsWarning(settingsMap, weekday, weekend));

        model.addAttribute("settings", settingsMap);
        model.addAttribute("platformNames", PLATFORM_NAMES);
        model.addAttribute("listings", listings);
        model.addAttribute("latestPrices", latestPrices);
        model.addAttribute("avgCompetitorPrice", avgPrice);
        model.addAttribute("properties", properties);
        model.addAttribute("recommendations", recommendations);

        return "pages/pricing/index";
    }

    @PostMapping("/competitors/add")
    public String addCompetitor(@RequestParam String name,
                                @RequestParam String url,
                                @RequestParam(required = false) String platform,
                                @RequestParam(required = false, defaultValue = "listing") String kind,
                                RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        var properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        var property = properties.isEmpty() ? null : properties.get(0);
        Long propertyId = property != null ? property.getId() : null;
        String city = property != null && property.getCity() != null ? property.getCity() : "";

        if ("search".equals(kind)) {
            String p = competitorService.detectPlatform(url, platform);
            competitorService.addSearch(tenantId, propertyId, p, url, name, city);
            redirect.addFlashAttribute("success", "Поиск конкурентов «" + name + "» добавлен");
        } else {
            competitorService.addListing(tenantId, propertyId, name, url, platform);
            redirect.addFlashAttribute("success", "Конкурент «" + name + "» добавлен");
        }
        return "redirect:/pricing";
    }

    @PostMapping("/competitors/{id}/delete")
    public String deleteCompetitor(@PathVariable Long id, RedirectAttributes redirect) {
        if (!competitorService.deleteListing(AuthContext.tenantId(), id)) throw Tenancy.notFound();
        redirect.addFlashAttribute("success", "Конкурент удалён");
        return "redirect:/pricing";
    }

    @PostMapping("/competitors/{id}/scrape")
    public String scrapeCompetitor(@PathVariable Long id, RedirectAttributes redirect) {
        var listing = competitorService.getListings(AuthContext.tenantId()).stream()
                .filter(l -> l.getId().equals(id)).findFirst().orElseThrow(Tenancy::notFound);
        var result = competitorService.scrapeOne(listing);
        if (result.success()) {
            redirect.addFlashAttribute("success", "Цена получена: " + result.price() + " ₽");
        } else {
            redirect.addFlashAttribute("success", "Не удалось получить цену: " + result.error());
        }
        return "redirect:/pricing";
    }

    /** Площадки конкурентов по-русски: в базе хранится код (avito, sutochno…). */
    static final Map<String, String> PLATFORM_NAMES = Map.of(
            "avito", "Авито", "sutochno", "Суточно", "cian", "Циан", "ostrovok", "Островок",
            "yandex", "Яндекс Путешествия", "other", "Другая");

    private static String rub(BigDecimal v) {
        return v == null ? "—" : v.setScale(0, RoundingMode.HALF_UP).toPlainString() + " ₽";
    }

    /**
     * Пределы из настроек не дают рекомендациям дойти до цены квартиры: цена ниже минимума или
     * выше максимума. Такие пределы остались у аккаунтов, созданных до фазы 2 (2000–8000 ₽).
     */
    static String boundsWarning(Map<String, String> s, BigDecimal weekday, BigDecimal weekend) {
        if (weekday == null) return null;
        Integer floor = positive(s.get("min_price_floor"));
        Integer ceil = positive(s.get("max_price_ceiling"));
        BigDecimal top = weekend != null && weekend.compareTo(weekday) > 0 ? weekend : weekday;
        BigDecimal low = weekend != null && weekend.compareTo(weekday) < 0 ? weekend : weekday;
        if (ceil != null && top.compareTo(BigDecimal.valueOf(ceil)) > 0) {
            return "Цена квартиры (" + rub(top) + ") выше максимальной цены в настройках (" + ceil
                    + " ₽): рекомендации не поднимутся выше " + ceil + " ₽. Поправьте или очистите "
                    + "предел в настройках — пустой означает «не выше 2,5 цены квартиры».";
        }
        if (floor != null && low.compareTo(BigDecimal.valueOf(floor)) < 0) {
            return "Цена квартиры (" + rub(low) + ") ниже минимальной цены в настройках (" + floor
                    + " ₽): рекомендации не опустятся ниже " + floor + " ₽. Поправьте или очистите "
                    + "предел в настройках — пустой означает «не ниже половины цены квартиры».";
        }
        return null;
    }

    private static Integer positive(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
