package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.security.Tenancy;
import ru.rentoptima.seo.SiteSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Раздел «Сотрудники»: доступ горничной к странице объекта (ссылка + PIN).
 * PIN хранится только хэшем, поэтому показать его нельзя — можно задать новый.
 */
@Controller
@RequestMapping("/staff")
@RequiredArgsConstructor
public class StaffController {

    private static final int MIN_PIN_LENGTH = 4;

    private final PropertyRepository propertyRepo;
    private final PasswordEncoder encoder;
    private final SiteSettings site;

    @GetMapping
    public String page(Model model, HttpServletRequest request) {
        Long tenantId = AuthContext.tenantId();
        String baseUrl = site.linkBase(request);

        List<HousekeeperAccess> items = new ArrayList<>();
        for (Property p : propertyRepo.findByTenantIdAndActiveTrue(tenantId)) {
            boolean hasPin = p.getHousekeeperPinHash() != null && !p.getHousekeeperPinHash().isBlank();
            items.add(new HousekeeperAccess(
                    p.getId(), p.getName(),
                    baseUrl + "/housekeeper/" + p.getHousekeeperCode(),
                    "housekeeper-url-" + p.getId(),
                    hasPin,
                    hasPin ? "PIN установлен" : "PIN не установлен — горничная не сможет войти",
                    hasPin ? "tag tag--green" : "tag tag--amber",
                    hasPin ? "Сменить PIN" : "Установить PIN",
                    p.getHousekeeperHorizonDays()));
        }

        model.addAttribute("activePage", "staff");
        model.addAttribute("items", items);
        model.addAttribute("horizonMin", Property.MIN_HOUSEKEEPER_HORIZON_DAYS);
        model.addAttribute("horizonMax", Property.MAX_HOUSEKEEPER_HORIZON_DAYS);
        return "pages/staff/index";
    }

    @PostMapping("/{propertyId}/pin")
    public String setPin(@PathVariable Long propertyId,
                         @RequestParam String pin,
                         RedirectAttributes redirect) {
        Property property = ownProperty(propertyId);
        String value = pin == null ? "" : pin.trim();
        if (value.length() < MIN_PIN_LENGTH) {
            redirect.addFlashAttribute("error", "PIN должен быть от " + MIN_PIN_LENGTH + " символов");
            return "redirect:/staff";
        }

        property.setHousekeeperPinHash(encoder.encode(value));
        propertyRepo.save(property);
        redirect.addFlashAttribute("success", "PIN для «" + property.getName() + "» обновлён");
        return "redirect:/staff";
    }

    /** Горизонт страницы горничной: на сколько дней вперёд она видит выезды. */
    @PostMapping("/{propertyId}/horizon")
    public String setHorizon(@PathVariable Long propertyId,
                             @RequestParam(required = false) Integer days,
                             RedirectAttributes redirect) {
        Property property = ownProperty(propertyId);
        if (days == null || days < Property.MIN_HOUSEKEEPER_HORIZON_DAYS
                || days > Property.MAX_HOUSEKEEPER_HORIZON_DAYS) {
            redirect.addFlashAttribute("error", "Число дней — от " + Property.MIN_HOUSEKEEPER_HORIZON_DAYS
                    + " до " + Property.MAX_HOUSEKEEPER_HORIZON_DAYS);
            return "redirect:/staff";
        }
        property.setHousekeeperHorizonDays(days);
        propertyRepo.save(property);
        redirect.addFlashAttribute("success", "Горничная видит уборки на " + days + " дн. вперёд — «"
                + property.getName() + "»");
        return "redirect:/staff";
    }

    /** Активный объект своего тенанта; чужой или удалённый — 404. */
    private Property ownProperty(Long propertyId) {
        Long tenantId = AuthContext.tenantId();
        return propertyRepo.findByTenantIdAndActiveTrue(tenantId).stream()
                .filter(p -> p.getId().equals(propertyId))
                .findFirst().orElseThrow(Tenancy::notFound);
    }

    public record HousekeeperAccess(Long propertyId, String propertyName,
                                    String url, String inputId,
                                    boolean hasPin, String pinStatus, String pinStatusCss,
                                    String pinButton, int horizonDays) {}
}
