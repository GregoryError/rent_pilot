package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Property CRUD.
 * <p>
 * Пишем через JdbcTemplate вместо JPA-репозитория намеренно: предыдущая
 * реализация через {@code propertyRepo.save(p)} после {@code p.setTenant(...)}
 * давала NULL в колонке tenant_id и падала на NOT NULL — Hibernate по какой-то
 * причине не забирал id из ManyToOne-ассоциации (воспроизводилось на пустой
 * staging БД, где нет seed-строк). Явный SQL с bind-параметром tenant_id
 * убирает неопределённость и не зависит от расхождений маппинга Property.
 * Список остаётся на JPA — там чтение, никаких сюрпризов с FK нет.
 */
@Slf4j
@Controller
@RequestMapping("/settings/properties")
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyRepository propertyRepo;
    private final JdbcTemplate jdbc;

    @GetMapping
    public String list(Model model) {
        Long tenantId = AuthContext.tenantId();
        model.addAttribute("activePage", "settings");
        model.addAttribute("properties", propertyRepo.findByTenantIdAndActiveTrue(tenantId));
        return "pages/settings/properties";
    }

    @PostMapping
    @Transactional
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String address,
                         @RequestParam(required = false) String city,
                         RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        if (tenantId == null) {
            throw new IllegalStateException("tenantId в сессии не установлен");
        }
        String cityValue = (city != null && !city.isBlank()) ? city : "Выборг";
        String feedbackCode = generateCode();
        String housekeeperCode = generateCode();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());

        // housekeeper_pin_hash и rc_object_id не заполняем — оба должны быть nullable
        // (в стек-трейсе tenant_id было единственным упомянутым NOT-NULL нарушением
        // при null-значениях в этих колонках).
        jdbc.update("""
                INSERT INTO properties
                    (tenant_id, name, address, city,
                     feedback_code, housekeeper_code,
                     active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                """,
                tenantId, name, address, cityValue,
                feedbackCode, housekeeperCode,
                now, now);

        log.info("Property создан: tenant={}, name='{}'", tenantId, name);
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
        String rcClean = (rcObjectId == null || rcObjectId.isBlank()) ? null : rcObjectId.trim();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        // WHERE tenant_id обеспечивает мультитенантную изоляцию — не даёт отредактировать чужой объект.
        int rows = jdbc.update("""
                UPDATE properties
                   SET name = ?, address = ?, city = ?, rc_object_id = ?, updated_at = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                name, address, city, rcClean, now, id, tenantId);
        if (rows == 0) {
            log.warn("Property update: 0 строк обновлено (id={}, tenant={})", id, tenantId);
        }
        redirect.addFlashAttribute("success", "Объект обновлён");
        return "redirect:/settings/properties";
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("""
                UPDATE properties
                   SET active = FALSE, updated_at = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                now, id, tenantId);
        redirect.addFlashAttribute("success", "Объект удалён");
        return "redirect:/settings/properties";
    }

    private String generateCode() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
