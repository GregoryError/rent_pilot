package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.security.AuthContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CRUD категорий номеров внутри property.
 * <p>
 * В отличие от Property, здесь используем JPA-репозиторий напрямую — сущность
 * UnitType не страдает от двойного маппинга tenant_id (нет ManyToOne, только
 * Long tenantId), сохранение работает штатно.
 * <p>
 * Все действия скоуплены к property через путь; ownership проверяется по
 * property.tenantId — так один тенант не может добавить категорию в чужой property.
 */
@Slf4j
@Controller
@RequestMapping("/settings/properties/{propertyId}/unit-types")
@RequiredArgsConstructor
public class UnitTypeController {

    private final UnitTypeRepository unitTypeRepo;
    private final PropertyRepository propertyRepo;

    @PostMapping
    @Transactional
    public String create(@PathVariable Long propertyId,
                         @RequestParam String name,
                         @RequestParam(required = false) Integer unitCount,
                         @RequestParam(required = false) BigDecimal basePrice,
                         @RequestParam(required = false) BigDecimal weekendPrice,
                         RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        Property property = propertyRepo.findById(propertyId).orElse(null);
        if (property == null || !tenantId.equals(property.getTenantId())) {
            redirect.addFlashAttribute("error", "Объект не найден");
            return "redirect:/settings/properties";
        }
        UnitType ut = new UnitType();
        ut.setTenantId(tenantId);
        ut.setPropertyId(propertyId);
        ut.setName(name != null ? name.trim() : "Основной");
        ut.setUnitCount(unitCount == null || unitCount < 1 ? 1 : unitCount);
        ut.setBasePrice(basePrice);
        ut.setWeekendPrice(weekendPrice);
        ut.setActive(true);
        ut.setCreatedAt(LocalDateTime.now());
        ut.setUpdatedAt(LocalDateTime.now());
        unitTypeRepo.save(ut);
        log.info("UnitType создан: tenant={}, property={}, name='{}'", tenantId, propertyId, ut.getName());
        redirect.addFlashAttribute("success", "Категория «" + ut.getName() + "» добавлена");
        return "redirect:/settings/properties";
    }

    @PostMapping("/{id}/edit")
    @Transactional
    public String update(@PathVariable Long propertyId,
                         @PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false) Integer unitCount,
                         @RequestParam(required = false) BigDecimal basePrice,
                         @RequestParam(required = false) BigDecimal weekendPrice,
                         RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        unitTypeRepo.findById(id).ifPresent(ut -> {
            // Скоуп по tenant + property — не даём тронуть чужую категорию.
            if (!tenantId.equals(ut.getTenantId()) || !propertyId.equals(ut.getPropertyId())) return;
            ut.setName(name != null ? name.trim() : ut.getName());
            ut.setUnitCount(unitCount == null || unitCount < 1 ? 1 : unitCount);
            ut.setBasePrice(basePrice);
            ut.setWeekendPrice(weekendPrice);
            ut.setUpdatedAt(LocalDateTime.now());
            unitTypeRepo.save(ut);
        });
        redirect.addFlashAttribute("success", "Категория обновлена");
        return "redirect:/settings/properties";
    }

    /**
     * Soft delete: active = false. Физическое удаление невозможно, пока висят
     * broни/blocks/каналы (FK), а даже когда возможно — лучше сохранить историю.
     */
    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long propertyId,
                         @PathVariable Long id,
                         RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        unitTypeRepo.findById(id).ifPresent(ut -> {
            if (!tenantId.equals(ut.getTenantId()) || !propertyId.equals(ut.getPropertyId())) return;
            ut.setActive(false);
            ut.setUpdatedAt(LocalDateTime.now());
            unitTypeRepo.save(ut);
        });
        redirect.addFlashAttribute("success", "Категория удалена");
        return "redirect:/settings/properties";
    }
}
