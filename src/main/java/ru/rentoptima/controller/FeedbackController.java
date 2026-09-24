package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import ru.rentoptima.entity.FeedbackAnswer;
import ru.rentoptima.entity.FeedbackQuestion;
import ru.rentoptima.entity.FeedbackResponse;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.FeedbackAnswerRepository;
import ru.rentoptima.repository.FeedbackQuestionRepository;
import ru.rentoptima.repository.FeedbackResponseRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.util.PdAnonymizer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class FeedbackController {

    private final PropertyRepository propertyRepo;
    private final FeedbackResponseRepository feedbackRepo;
    private final FeedbackQuestionRepository questionRepo;
    private final FeedbackAnswerRepository answerRepo;

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
    @Transactional
    public ResponseEntity<Map<String, String>> submitFeedback(@RequestBody FeedbackRequest request) {
        Property property = propertyRepo.findByFeedbackCode(request.propertyCode()).orElse(null);
        if (property == null) return ResponseEntity.badRequest().body(Map.of("error", "Property not found"));

        FeedbackResponse response = feedbackRepo.findBySessionId(request.sessionId())
                .orElseGet(() -> {
                    FeedbackResponse r = new FeedbackResponse();
                    r.setPropertyId(property.getId());
                    r.setSessionId(request.sessionId());
                    return r;
                });
        response.setGuestName(PdAnonymizer.toInitial(request.guestName()));
        response.setUpdatedAt(LocalDateTime.now());
        response.setCompleted(Boolean.TRUE.equals(request.completed()));
        response = feedbackRepo.save(response);

        List<FeedbackQuestion> questions = questionRepo
                .findByTenantIdAndActiveTrueOrderBySortOrderAsc(property.getTenant().getId());

        List<FeedbackAnswer> existing = answerRepo.findByResponseIdOrderByAnsweredAtAsc(response.getId());
        if (!existing.isEmpty()) answerRepo.deleteAll(existing);

        for (FeedbackQuestion q : questions) {
            FeedbackAnswer a = new FeedbackAnswer();
            a.setResponseId(response.getId());
            a.setQuestionId(q.getId());
            a.setAnsweredAt(LocalDateTime.now());

            String txt = q.getQuestionText().toLowerCase();
            if ("SCALE".equals(q.getQuestionType())) {
                if (txt.contains("чист")) a.setNumericValue(request.cleanliness());
                else if (txt.contains("инструкц")) a.setNumericValue(request.instructions());
                else if (txt.contains("общая") || txt.contains("оценка")) a.setNumericValue(request.overall());
            } else {
                if (txt.contains("понравил")) a.setTextValue(request.liked());
                else if (txt.contains("улучш")) a.setTextValue(request.improve());
                else if (txt.contains("комментар")) a.setTextValue(request.comments());
            }
            if (a.getNumericValue() != null
                    || (a.getTextValue() != null && !a.getTextValue().isBlank())) {
                answerRepo.save(a);
            }
        }

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
