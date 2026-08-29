package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.CompetitorService;
import ru.rentoptima.service.SettingsService;

@Controller
@RequestMapping("/pricing")
@RequiredArgsConstructor
public class PricingController {

    private final CompetitorService competitorService;
    private final PropertyRepository propertyRepo;
    private final SettingsService settings;

    @GetMapping
    public String pricing(Model model) {
        Long tenantId = AuthContext.tenantId();
        var settingsMap = settings.getSettingsMap(tenantId);
        var listings = competitorService.getListings(tenantId);
        var latestPrices = competitorService.getLatestPrices(tenantId);
        var avgPrice = competitorService.getAverageCompetitorPrice(tenantId);
        var properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);

        model.addAttribute("activePage", "pricing");
        model.addAttribute("settings", settingsMap);
        model.addAttribute("listings", listings);
        model.addAttribute("latestPrices", latestPrices);
        model.addAttribute("avgCompetitorPrice", avgPrice);
        model.addAttribute("properties", properties);

        return "pages/pricing/index";
    }

    @PostMapping("/competitors/add")
    public String addCompetitor(@RequestParam String name,
                                 @RequestParam String url,
                                 @RequestParam(required = false) String platform,
                                 RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        var properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        Long propertyId = properties.isEmpty() ? null : properties.get(0).getId();

        competitorService.addListing(tenantId, propertyId, name, url, platform);
        redirect.addFlashAttribute("success", "Конкурент «" + name + "» добавлен");
        return "redirect:/pricing";
    }

    @PostMapping("/competitors/{id}/delete")
    public String deleteCompetitor(@PathVariable Long id, RedirectAttributes redirect) {
        competitorService.deleteListing(id);
        redirect.addFlashAttribute("success", "Конкурент удалён");
        return "redirect:/pricing";
    }

    @PostMapping("/competitors/{id}/scrape")
    public String scrapeCompetitor(@PathVariable Long id, RedirectAttributes redirect) {
        var listing = competitorService.getListings(AuthContext.tenantId()).stream()
                .filter(l -> l.getId().equals(id)).findFirst();

        if (listing.isPresent()) {
            var result = competitorService.scrapeOne(listing.get());
            if (result.success()) {
                redirect.addFlashAttribute("success", "Цена получена: " + result.price() + " ₽");
            } else {
                redirect.addFlashAttribute("success", "Не удалось получить цену: " + result.error());
            }
        }
        return "redirect:/pricing";
    }
}
