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
import ru.rentoptima.security.Tenancy;
import ru.rentoptima.service.FeedbackAnalyticsService;
import ru.rentoptima.seo.SiteSettings;

import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/feedback-admin")
@RequiredArgsConstructor
public class FeedbackAdminController {

    private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter.ofPattern("dd.MM");

    private final PropertyRepository propertyRepo;
    private final ru.rentoptima.repository.BookingRepository bookingRepo;
    private final FeedbackResponseRepository feedbackRepo;
    private final FeedbackAnswerRepository answerRepo;
    private final FeedbackQuestionRepository questionRepo;
    private final FeedbackAnalyticsService analytics;
    private final SiteSettings site;

    @GetMapping
    public String page(@RequestParam(name = "property", required = false) Long propertyId,
                       Model model, HttpServletRequest request) {
        model.addAttribute("activePage", "feedback");
        Long tenantId = AuthContext.tenantId();
        List<Property> properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        // Отзывы показываются по одному объекту; раньше это всегда был первый, и отзывы
        // остальных объектов сохранялись, но увидеть их было негде.
        Property property = propertyId == null ? (properties.isEmpty() ? null : properties.get(0))
                // чужой или удалённый объект в адресе — 404
                : properties.stream().filter(p -> p.getId().equals(propertyId)).findFirst()
                        .orElseThrow(Tenancy::notFound);

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
            // С карточки в квартире: подпись и, если удалось определить, даты брони
            boolean card = FeedbackResponse.SOURCE_CARD.equals(r.getSource());
            String cardLabel = null;
            if (card) {
                cardLabel = "с карточки для гостей";
                if (r.getBookingId() != null) {
                    cardLabel += bookingRepo.findById(r.getBookingId())
                            .filter(b -> tenantId.equals(b.getTenant().getId()))
                            .map(b -> " · бронь " + b.getCheckIn().format(DAY) + " — " + b.getCheckOut().format(DAY))
                            .orElse("");
                } else {
                    cardLabel += " · бронь не определена";
                }
            }
            item.put("cardLabel", cardLabel);
            item.put("answers", allAnswers);
            item.put("avgRating", Math.round(avg * 10) / 10.0);
            return item;
        }).collect(Collectors.toList());

        List<PropertyTab> tabs = new ArrayList<>();
        if (properties.size() > 1) {
            for (Property p : properties) {
                int count = p.getId().equals(property.getId())
                        ? responses.size()
                        : feedbackRepo.findByPropertyIdAndCompletedTrueOrderByCreatedAtDesc(p.getId()).size();
                tabs.add(new PropertyTab(p.getId(), p.getName() + " · " + count,
                        p.getId().equals(property.getId()) ? "btn btn-primary" : "btn btn-ghost"));
            }
        }
        model.addAttribute("propertyTabs", tabs);
        model.addAttribute("property", property);
        model.addAttribute("items", items);
        model.addAttribute("averageRating", analytics.averageRating(tenantId, property.getId()));

        // Ссылки на анкету — по всем объектам и сразу полным адресом: их копируют в QR и мессенджеры
        String baseUrl = site.linkBase(request);
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
        // Отзыв должен относиться к объекту текущего tenant'а; чужой или несуществующий — 404
        FeedbackResponse r = feedbackRepo.findById(id)
                .filter(x -> propertyRepo.findByTenantIdAndActiveTrue(tenantId).stream()
                        .anyMatch(p -> p.getId().equals(x.getPropertyId())))
                .orElseThrow(Tenancy::notFound);
        String back = "redirect:/feedback-admin?property=" + r.getPropertyId();
        r.setShowToHousekeeper(!Boolean.TRUE.equals(r.getShowToHousekeeper()));
        feedbackRepo.save(r);
        return back;
    }

    public record PropertyTab(Long id, String label, String cssClass) {}

    public record FeedbackLink(String propertyName, String url, String inputId, String qrFileName) {}
}
