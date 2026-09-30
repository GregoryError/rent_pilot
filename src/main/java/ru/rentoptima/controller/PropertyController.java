package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
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

    /**
     * Транзакция обязательна: без неё tenantRepo.findById возвращал бы уже отсоединённый
     * Tenant, а последующий propertyRepo.save открывал бы свою сессию — Hibernate биндил
     * бы tenant_id как null и падал бы на NOT NULL (это и наблюдалось на пустой staging БД).
     * getReferenceById даёт прокси с уже известным id без похода в БД: для INSERT этого
     * достаточно, а сам факт наличия tenant проверяется каскадно на FK.
     */
    @PostMapping
    @Transactional
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String address,
                         @RequestParam(required = false) String city,
                         RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        Tenant tenant = tenantRepo.getReferenceById(tenantId);

        Property p = new Property();
        p.setTenant(tenant);
        p.setName(name);
        p.setAddress(address);
        p.setCity(city != null && !city.isBlank() ? city : "Выборг");
        // RC ID сознательно не принимаем на форме создания — уходим от привязки к RC.
        // Для существующих prod-объектов поле остаётся редактируемым в форме edit.
        p.setRcObjectId(null);
        p.setFeedbackCode(generateCode());
        p.setHousekeeperCode(generateCode());
        p.setActive(true);
        p.setUpdatedAt(LocalDateTime.now());

        propertyRepo.save(p);
        redirect.addFlashAttribute("success", "Объект «" + name + "» добавлен");
        return "redirect:/settings/properties";
    }

    @PostMapping("/{id}/edit")
    @Transactional
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false) String address,
                         @RequestParam(required = false) String city,
                         @RequestParam(required = false) String rcObjectId,
                         RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        propertyRepo.findById(id).ifPresent(p -> {
            // Мультитенант: не даём отредактировать чужой объект даже по угаданному id.
            if (p.getTenant() == null || !tenantId.equals(p.getTenant().getId())) return;
            p.setName(name);
            p.setAddress(address);
            p.setCity(city);
            // rcObjectId правится только через edit, для legacy-объектов; на форме создания его нет.
            p.setRcObjectId(rcObjectId == null || rcObjectId.isBlank() ? null : rcObjectId.trim());
            p.setUpdatedAt(LocalDateTime.now());
            propertyRepo.save(p);
        });
        redirect.addFlashAttribute("success", "Объект обновлён");
        return "redirect:/settings/properties";
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        propertyRepo.findById(id).ifPresent(p -> {
            if (p.getTenant() == null || !tenantId.equals(p.getTenant().getId())) return;
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
