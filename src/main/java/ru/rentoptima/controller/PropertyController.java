package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.TenantRepository;
import ru.rentoptima.security.AuthContext;

import java.time.LocalDateTime;
import java.util.UUID;

@Controller
@RequestMapping("/settings/properties")
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyRepository propertyRepo;
    private final TenantRepository tenantRepo;

    @GetMapping
    public String list(Model model) {
        Long tenantId = AuthContext.tenantId();
        model.addAttribute("activePage", "settings");
        model.addAttribute("properties", propertyRepo.findByTenantIdAndActiveTrue(tenantId));
        return "pages/settings/properties";
    }

    @PostMapping
    public String create(@RequestParam String name,
                          @RequestParam(required = false) String address,
                          @RequestParam(required = false) String city,
                          @RequestParam(required = false) String rcObjectId,
                          RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        Tenant tenant = tenantRepo.findById(tenantId).orElseThrow();

        Property p = new Property();
        p.setTenant(tenant);
        p.setName(name);
        p.setAddress(address);
        p.setCity(city != null && !city.isBlank() ? city : "Выборг");
        p.setRcObjectId(rcObjectId);
        p.setFeedbackCode(generateCode());
        p.setHousekeeperCode(generateCode());
        p.setActive(true);
        p.setUpdatedAt(LocalDateTime.now());

        propertyRepo.save(p);
        redirect.addFlashAttribute("success", "Объект «" + name + "» добавлен");
        return "redirect:/settings/properties";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id,
                          @RequestParam String name,
                          @RequestParam(required = false) String address,
                          @RequestParam(required = false) String city,
                          @RequestParam(required = false) String rcObjectId,
                          RedirectAttributes redirect) {
        propertyRepo.findById(id).ifPresent(p -> {
            p.setName(name);
            p.setAddress(address);
            p.setCity(city);
            p.setRcObjectId(rcObjectId);
            p.setUpdatedAt(LocalDateTime.now());
            propertyRepo.save(p);
        });
        redirect.addFlashAttribute("success", "Объект обновлён");
        return "redirect:/settings/properties";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        propertyRepo.findById(id).ifPresent(p -> {
            p.setActive(false);
            p.setUpdatedAt(LocalDateTime.now());
            propertyRepo.save(p);
        });
        redirect.addFlashAttribute("success", "Объект удалён");
        return "redirect:/settings/properties";
    }

    private String generateCode() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
