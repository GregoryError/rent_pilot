package ru.rentoptima.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;
import ru.rentoptima.entity.FeedbackAnswer;
import ru.rentoptima.entity.FeedbackQuestion;
import ru.rentoptima.entity.FeedbackResponse;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.FeedbackAnswerRepository;
import ru.rentoptima.repository.FeedbackQuestionRepository;
import ru.rentoptima.repository.FeedbackResponseRepository;
import ru.rentoptima.repository.PropertyRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class HousekeeperController {

    private static final int FRESH_REVIEW_DAYS = 5;

    private final PropertyRepository propertyRepo;
    private final BookingRepository bookingRepo;
    private final FeedbackResponseRepository feedbackRepo;
    private final FeedbackAnswerRepository answerRepo;
    private final FeedbackQuestionRepository questionRepo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @GetMapping("/housekeeper/{code}")
    public String housekeeperPage(@PathVariable String code,
                                  @CookieValue(value = "hk_auth", required = false) String cookieAuth,
                                  @RequestParam(required = false) String error,
                                  @RequestParam(required = false, defaultValue = "schedule") String tab,
                                  Model model) {
        Property property = propertyRepo.findByHousekeeperCode(code).orElse(null);
        if (property == null) return "error/404";

        if (property.getHousekeeperPinHash() == null || property.getHousekeeperPinHash().isBlank()) {
            model.addAttribute("code", code);
            model.addAttribute("pinNotSet", true);
            return "pages/housekeeper/login";
        }

        boolean authorized = cookieAuth != null
                && cookieAuth.equals(cookieValueFor(property));

        if (!authorized) {
            model.addAttribute("code", code);
            model.addAttribute("error", error != null);
            return "pages/housekeeper/login";
        }

        LocalDate now = LocalDate.now();

        // Schedule — ближайшие выезды
        var upcoming = bookingRepo.findUpcomingCheckouts(property.getId(), now);

        // Reviews
        List<FeedbackResponse> feedbacks = feedbackRepo
                .findByPropertyIdAndShowToHousekeeperTrueOrderByCreatedAtDesc(property.getId());

        // Fresh review flag (для колокольчика)
        LocalDateTime freshThreshold = LocalDateTime.now().minusDays(FRESH_REVIEW_DAYS);
        boolean hasFreshReview = feedbacks.stream()
                .anyMatch(f -> f.getCreatedAt() != null && f.getCreatedAt().isAfter(freshThreshold));

        List<FeedbackQuestion> questions = questionRepo
                .findByTenantIdAndActiveTrueOrderBySortOrderAsc(property.getTenant().getId());
        Map<Long, FeedbackQuestion> qMap = questions.stream()
                .collect(Collectors.toMap(FeedbackQuestion::getId, q -> q));

        List<Map<String, Object>> reviews = feedbacks.stream().map(r -> {
            List<FeedbackAnswer> answers = answerRepo.findByResponseIdOrderByAnsweredAtAsc(r.getId());
            List<Map<String, Object>> texts = answers.stream()
                    .filter(a -> a.getTextValue() != null && !a.getTextValue().isBlank())
                    .map(a -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("question", qMap.get(a.getQuestionId()) != null
                                ? qMap.get(a.getQuestionId()).getQuestionText() : "?");
                        m.put("value", a.getTextValue());
                        return m;
                    })
                    .collect(Collectors.toList());
            List<Map<String, Object>> scales = answers.stream()
                    .filter(a -> a.getNumericValue() != null)
                    .map(a -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("question", qMap.get(a.getQuestionId()) != null
                                ? qMap.get(a.getQuestionId()).getQuestionText() : "?");
                        m.put("value", a.getNumericValue());
                        return m;
                    })
                    .collect(Collectors.toList());
            Map<String, Object> item = new HashMap<>();
            item.put("response", r);
            item.put("texts", texts);
            item.put("scales", scales);
            item.put("isFresh", r.getCreatedAt() != null && r.getCreatedAt().isAfter(freshThreshold));
            return item;
        }).collect(Collectors.toList());

        model.addAttribute("property", property);
        model.addAttribute("bookings", upcoming);
        model.addAttribute("reviews", reviews);
        model.addAttribute("hasFreshReview", hasFreshReview);
        model.addAttribute("today", now);
        model.addAttribute("tab", tab);
        model.addAttribute("code", code);
        return "pages/housekeeper/index";
    }

    @PostMapping("/housekeeper/{code}/login")
    public String login(@PathVariable String code,
                        @RequestParam String pin,
                        HttpServletResponse response) {
        Property property = propertyRepo.findByHousekeeperCode(code).orElse(null);
        if (property == null) return "error/404";

        if (property.getHousekeeperPinHash() == null
                || !encoder.matches(pin, property.getHousekeeperPinHash())) {
            return "redirect:/housekeeper/" + code + "?error=1";
        }

        Cookie c = new Cookie("hk_auth", cookieValueFor(property));
        c.setPath("/housekeeper/" + code);
        c.setHttpOnly(true);
        c.setMaxAge(7 * 24 * 3600);
        response.addCookie(c);
        return "redirect:/housekeeper/" + code;
    }

    private String cookieValueFor(Property property) {
        String raw = property.getHousekeeperPinHash() + ":" + property.getId();
        return DigestUtils.md5DigestAsHex(raw.getBytes());
    }
}