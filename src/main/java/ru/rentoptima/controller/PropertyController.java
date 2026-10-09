package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.security.AuthContext;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Property CRUD.
 * <p>
 * Пишем через JdbcTemplate вместо JPA-репозитория намеренно — см. комментарий
 * от предыдущего фикса: у Property.java двойной маппинг на tenant_id (ManyToOne
 * с insertable=false плюс @Column-поле tenantId), и попытка сохранить через
 * setTenant не работала. Список загружаем через JPA, там читать безопасно.
 * <p>
 * unit_types для каждой property подгружаем в модель здесь же, чтобы шаблон
 * мог показать их под карточкой объекта без дополнительных запросов.
 */
@Slf4j
@Controller
@RequestMapping("/settings/properties")
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyRepository propertyRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final JdbcTemplate jdbc;

    @GetMapping
    public String list(Model model, jakarta.servlet.http.HttpServletRequest request) {
        Long tenantId = AuthContext.tenantId();
        model.addAttribute("baseUrl", org.springframework.web.servlet.support.ServletUriComponentsBuilder
                .fromContextPath(request).build().toUriString());
        // Группируем unit_type по propertyId — шаблон достанет по ключу для каждой карточки.
        Map<Long, List<UnitType>> unitTypesByProperty = unitTypeRepo
                .findByTenantIdAndActiveTrue(tenantId).stream()
                .collect(Collectors.groupingBy(UnitType::getPropertyId));
        model.addAttribute("activePage", "settings");
        model.addAttribute("properties", propertyRepo.findByTenantIdAndActiveTrue(tenantId));
        model.addAttribute("unitTypesByProperty", unitTypesByProperty);
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
                         RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int rows = jdbc.update("""
                UPDATE properties
                   SET name = ?, address = ?, city = ?, updated_at = ?
                 WHERE id = ? AND tenant_id = ?
                """,
                name, address, city, now, id, tenantId);
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
