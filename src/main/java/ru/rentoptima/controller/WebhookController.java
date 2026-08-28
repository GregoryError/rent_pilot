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
        log.info("RC webhook received: {}", payload.has("event") ? payload.get("event").asText() : "unknown");
        log.debug("RC webhook payload: {}", payload);
        try {
            webhookService.processRcEvent(payload);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error processing RC webhook", e);
            return ResponseEntity.ok("OK"); // Always return 200 to avoid retries
        }
    }
}
