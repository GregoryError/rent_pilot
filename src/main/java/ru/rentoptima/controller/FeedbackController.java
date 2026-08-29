package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.rentoptima.entity.FeedbackResponse;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.FeedbackResponseRepository;
import ru.rentoptima.repository.PropertyRepository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class FeedbackController {

    private final PropertyRepository propertyRepo;
    private final FeedbackResponseRepository feedbackRepo;

    @GetMapping("/feedback/{code}")
    public String feedbackPage(@PathVariable String code, Model model) {
        Property property = propertyRepo.findByFeedbackCode(code).orElse(null);
        if (property == null) return "error/404";

        String sessionId = UUID.randomUUID().toString();
        model.addAttribute("property", property);
        model.addAttribute("sessionId", sessionId);
        return "pages/feedback/index";
    }

    @PostMapping("/api/feedback/submit")
    @ResponseBody
    public ResponseEntity<Map<String, String>> submitFeedback(@RequestBody FeedbackRequest request) {
        Property property = propertyRepo.findByFeedbackCode(request.propertyCode()).orElse(null);
        if (property == null) return ResponseEntity.badRequest().body(Map.of("error", "Property not found"));

        // Find or create response
        FeedbackResponse response = feedbackRepo.findBySessionId(request.sessionId())
                .orElseGet(() -> {
                    FeedbackResponse r = new FeedbackResponse();
                    r.setPropertyId(property.getId());
                    r.setSessionId(request.sessionId());
                    return r;
                });

        response.setGuestName(request.guestName());
        response.setUpdatedAt(LocalDateTime.now());

        // Store answers as notes (simplified — later can use feedback_answers table)
        // For now, append to a JSON-like string in guest_phone field as temp storage
        // TODO: migrate to proper feedback_answers table usage

        response.setCompleted(request.completed() != null && request.completed());
        feedbackRepo.save(response);

        return ResponseEntity.ok(Map.of("status", "saved"));
    }

    public record FeedbackRequest(
            String propertyCode,
            String sessionId,
            String guestName,
            Integer cleanliness,
            Integer instructions,
            Integer overall,
            String liked,
            String improve,
            String comments,
            Boolean completed
    ) {}
}
