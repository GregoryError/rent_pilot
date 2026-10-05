package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
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
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.FeedbackAnalyticsService;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

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

    @GetMapping
    public String page(Model model, HttpServletRequest request) {
        model.addAttribute("activePage", "feedback");
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

        // Ссылки на анкету — по всем объектам и сразу полным адресом: их копируют в QR и мессенджеры
        String baseUrl = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        List<FeedbackLink> links = new ArrayList<>();
        for (Property p : properties) {
            links.add(new FeedbackLink(p.getName(),
                    baseUrl + "/feedback/" + p.getFeedbackCode(), "feedback-url-" + p.getId(),
                    "Анкета " + p.getName()));
        }
        model.addAttribute("feedbackLinks", links);
        return "pages/feedback-admin/index";
    }

    @PostMapping("/{id}/toggle-housekeeper")
    public String toggleHousekeeper(@PathVariable Long id) {
        Long tenantId = AuthContext.tenantId();
        FeedbackResponse r = feedbackRepo.findById(id).orElseThrow();
        // Отзыв должен относиться к объекту текущего tenant'а
        boolean own = propertyRepo.findByTenantIdAndActiveTrue(tenantId).stream()
                .anyMatch(p -> p.getId().equals(r.getPropertyId()));
        if (!own) return "redirect:/feedback-admin";
        r.setShowToHousekeeper(!Boolean.TRUE.equals(r.getShowToHousekeeper()));
        feedbackRepo.save(r);
        return "redirect:/feedback-admin";
    }

    public record FeedbackLink(String propertyName, String url, String inputId, String qrFileName) {}
}
