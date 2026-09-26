package ru.rentoptima.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.rentoptima.entity.ManualOverride;
import ru.rentoptima.repository.ManualOverrideRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.AiChatService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatController {

    private final AiChatService aiChatService;
    private final ManualOverrideRepository overrideRepo;
    private final PropertyRepository propertyRepo;
    private final ObjectMapper objectMapper;

    @GetMapping("/chat")
    public String chatPage(Model model) {
        model.addAttribute("activePage", "chat");
        return "pages/chat/index";
    }

    @PostMapping("/api/chat")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody ChatRequest request) {
        Long tenantId = AuthContext.tenantId();
        var response = aiChatService.chat(tenantId, request.message(), request.history());

        Map<String, Object> result = new HashMap<>();
        result.put("content", response.content());
        result.put("tokens", response.tokens());

        // Serialize pending actions so front end can show confirm dialogs
        if (response.pendingActions() != null && !response.pendingActions().isEmpty()) {
            result.put("actions", response.pendingActions());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/chat/apply-action")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> applyAction(@RequestBody ActionRequest req) {
        Long tenantId = AuthContext.tenantId();
        var properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        Long propertyId = properties.isEmpty() ? null : properties.get(0).getId();

        try {
            JsonNode action = objectMapper.readTree(req.actionJson());
            String type = action.path("type").asText();

            if (!isValidType(type)) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Неподдерживаемый тип действия: " + type));
            }

            Map<String, Object> params = objectMapper.convertValue(
                    action.path("params"), Map.class);

            LocalDateTime expiresAt = null;
            String expiresStr = action.path("expires_at").asText(null);
            if (expiresStr != null && !expiresStr.isBlank()) {
                try {
                    expiresAt = LocalDate.parse(expiresStr).atStartOfDay();
                    // Ограничение — не дальше 6 месяцев
                    if (expiresAt.isAfter(LocalDateTime.now().plusMonths(6))) {
                        return ResponseEntity.badRequest().body(
                                Map.of("error", "Срок действия не может быть больше 6 месяцев"));
                    }
                } catch (Exception e) {
                    return ResponseEntity.badRequest().body(
                            Map.of("error", "Некорректный expires_at: " + expiresStr));
                }
            }
            if (expiresAt == null) {
                expiresAt = LocalDateTime.now().plusMonths(1);
            }

            ManualOverride o = new ManualOverride();
            o.setTenantId(tenantId);
            o.setPropertyId(propertyId);
            o.setOverrideType(type);
            o.setParams(params);
            o.setDescription(action.path("description").asText(""));
            o.setActive(true);
            o.setOrigin("ai_chat");
            o.setExpiresAt(expiresAt);
            overrideRepo.save(o);

            log.info("Manual override applied: type={}, description={}",
                    type, o.getDescription());
            return ResponseEntity.ok(Map.of(
                    "status", "applied",
                    "id", o.getId(),
                    "description", o.getDescription()
            ));
        } catch (Exception e) {
            log.error("Failed to apply action: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/overrides/{id}/cancel")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> cancelOverride(@PathVariable Long id) {
        Long tenantId = AuthContext.tenantId();
        ManualOverride o = overrideRepo.findById(id).orElse(null);
        if (o == null || !o.getTenantId().equals(tenantId)) {
            return ResponseEntity.notFound().build();
        }
        o.setActive(false);
        o.setCancelledAt(LocalDateTime.now());
        overrideRepo.save(o);
        return ResponseEntity.ok(Map.of("status", "cancelled"));
    }


    private boolean isValidType(String type) {
        return List.of("price_multiplier", "min_stay_override").contains(type);
    }

    public record ChatRequest(String message, List<Map<String, String>> history) {}
    public record ActionRequest(String actionJson) {}
}
