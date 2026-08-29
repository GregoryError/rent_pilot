package ru.rentoptima.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.rentoptima.service.WebhookService;

@Slf4j
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    @PostMapping("/rc")
    public ResponseEntity<String> handleRcWebhook(@RequestBody JsonNode payload) {
        String action = payload.has("action") ? payload.get("action").asText() : "unknown";
        String status = payload.has("status") ? payload.get("status").asText() : "unknown";
        log.info("RC webhook received: action={}, status={}", action, status);

        try {
            webhookService.processRcEvent(payload);
        } catch (Exception e) {
            log.error("Error processing RC webhook: {}", e.getMessage(), e);
        }

        // Always return 200 to prevent RC from retrying
        return ResponseEntity.ok("OK");
    }
}
