package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.entity.FeedbackAnswer;
import ru.rentoptima.entity.FeedbackQuestion;
import ru.rentoptima.entity.FeedbackResponse;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.FeedbackAnswerRepository;
import ru.rentoptima.repository.FeedbackQuestionRepository;
import ru.rentoptima.repository.FeedbackResponseRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.FeedbackAnalyticsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/feedback-admin")
@RequiredArgsConstructor
public class FeedbackAdminController {

    private final PropertyRepository propertyRepo;
    private final FeedbackResponseRepository feedbackRepo;
    private final FeedbackAnswerRepository answerRepo;
    private final FeedbackQuestionRepository questionRepo;
    private final FeedbackAnalyticsService analytics;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @GetMapping
    public String page(Model model) {
        Long tenantId = AuthContext.tenantId();
        List<Property> properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        Property property = properties.isEmpty() ? null : properties.get(0);

        if (property == null) {
            model.addAttribute("property", null);
            return "pages/feedback-admin/index";
        }

        List<FeedbackResponse> responses = feedbackRepo
                .findByPropertyIdAndCompletedTrueOrderByCreatedAtDesc(property.getId());

        List<FeedbackQuestion> questions = questionRepo
                .findByTenantIdAndActiveTrueOrderBySortOrderAsc(tenantId);
        Map<Long, FeedbackQuestion> qMap = questions.stream()
                .collect(Collectors.toMap(FeedbackQuestion::getId, q -> q));

        List<Map<String, Object>> items = responses.stream().map(r -> {
            List<FeedbackAnswer> answers = answerRepo.findByResponseIdOrderByAnsweredAtAsc(r.getId());
            Double avg = answers.stream()
                    .filter(a -> a.getNumericValue() != null)
                    .mapToInt(FeedbackAnswer::getNumericValue)
                    .average().orElse(0);

            List<Map<String, Object>> allAnswers = answers.stream().map(a -> {
                Map<String, Object> m = new HashMap<>();
                FeedbackQuestion q = qMap.get(a.getQuestionId());
                m.put("question", q != null ? q.getQuestionText() : "?");
                m.put("type", q != null ? q.getQuestionType() : "TEXT");
                m.put("numeric", a.getNumericValue());
                m.put("text", a.getTextValue());
                return m;
            }).collect(Collectors.toList());

            Map<String, Object> item = new HashMap<>();
            item.put("response", r);
            item.put("answers", allAnswers);
            item.put("avgRating", Math.round(avg * 10) / 10.0);
            return item;
        }).collect(Collectors.toList());

        model.addAttribute("property", property);
        model.addAttribute("items", items);
        model.addAttribute("averageRating", analytics.averageRating(tenantId, property.getId()));
        model.addAttribute("housekeeperUrl", "/housekeeper/" + property.getHousekeeperCode());
        model.addAttribute("hasPin", property.getHousekeeperPinHash() != null
                && !property.getHousekeeperPinHash().isBlank());
        return "pages/feedback-admin/index";
    }

    @PostMapping("/{id}/toggle-housekeeper")
    public String toggleHousekeeper(@PathVariable Long id) {
        FeedbackResponse r = feedbackRepo.findById(id).orElseThrow();
        r.setShowToHousekeeper(!Boolean.TRUE.equals(r.getShowToHousekeeper()));
        feedbackRepo.save(r);
        return "redirect:/feedback-admin";
    }

    @PostMapping("/set-pin")
    public String setPin(@RequestParam String pin, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        Property property = propertyRepo.findByTenantIdAndActiveTrue(tenantId).stream()
                .findFirst().orElseThrow();

        if (pin == null || pin.length() < 4) {
            redirect.addFlashAttribute("error", "PIN должен быть от 4 символов");
            return "redirect:/feedback-admin";
        }

        property.setHousekeeperPinHash(encoder.encode(pin));
        propertyRepo.save(property);
        redirect.addFlashAttribute("success", "PIN горничной обновлён");
        return "redirect:/feedback-admin";
    }
}
