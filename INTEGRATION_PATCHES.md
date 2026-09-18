# Патчи существующих файлов

## 1. FeedbackController.java — переделать submitFeedback чтобы писал в feedback_answers

Замени submitFeedback полностью на:

```java
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
        response.setGuestName(request.guestName());
        response.setUpdatedAt(LocalDateTime.now());
        response.setCompleted(Boolean.TRUE.equals(request.completed()));
        feedbackRepo.save(response);

        // Load questions and write answers
        List<FeedbackQuestion> questions = questionRepo
                .findByTenantIdAndActiveTrueOrderBySortOrderAsc(property.getTenantId());

        // Remove existing answers for this response (allow re-submit)
        answerRepo.deleteAll(answerRepo.findByResponseIdOrderByAnsweredAtAsc(response.getId()));

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
            } else { // TEXT
                if (txt.contains("понравил")) a.setTextValue(request.liked());
                else if (txt.contains("улучш")) a.setTextValue(request.improve());
                else if (txt.contains("комментар")) a.setTextValue(request.comments());
            }
            if (a.getNumericValue() != null || (a.getTextValue() != null && !a.getTextValue().isBlank())) {
                answerRepo.save(a);
            }
        }

        return ResponseEntity.ok(Map.of("status", "saved"));
    }
```

Добавь в поля контроллера:
```java
private final FeedbackQuestionRepository questionRepo;
private final FeedbackAnswerRepository answerRepo;
```

Плюс импорты — `List`, `FeedbackQuestion`, `FeedbackAnswer`, `Transactional`.

---

## 2. HousekeeperController.java — защита PIN + смена URL на housekeeper_code

**Замени класс целиком** (шаблон):

```java
@Controller
@RequiredArgsConstructor
public class HousekeeperController {

    private final PropertyRepository propertyRepo;
    private final FeedbackResponseRepository feedbackRepo;
    private final FeedbackAnswerRepository answerRepo;
    private final FeedbackQuestionRepository questionRepo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @GetMapping("/housekeeper/{code}")
    public String housekeeperPage(@PathVariable String code,
                                   @CookieValue(value = "hk_auth", required = false) String cookieAuth,
                                   Model model) {
        Property property = propertyRepo.findByHousekeeperCode(code).orElse(null);
        if (property == null) return "error/404";

        // Verify cookie signature (property id + pin hash)
        boolean authorized = property.getHousekeeperPinHash() == null // no pin — free access
                || (cookieAuth != null && cookieAuth.equals(cookieValueFor(property)));

        if (!authorized) {
            model.addAttribute("code", code);
            return "pages/housekeeper/login";
        }

        List<FeedbackResponse> feedbacks = feedbackRepo
                .findByPropertyIdAndShowToHousekeeperTrueOrderByCreatedAtDesc(property.getId());

        var questions = questionRepo.findByTenantIdAndActiveTrueOrderBySortOrderAsc(property.getTenantId());
        var qMap = questions.stream().collect(java.util.stream.Collectors.toMap(
                FeedbackQuestion::getId, q -> q));

        var withAnswers = feedbacks.stream().map(r -> {
            var answers = answerRepo.findByResponseIdOrderByAnsweredAtAsc(r.getId());
            var texts = answers.stream()
                    .filter(a -> a.getTextValue() != null && !a.getTextValue().isBlank())
                    .map(a -> Map.of(
                            "question", qMap.get(a.getQuestionId()).getQuestionText(),
                            "value", a.getTextValue()))
                    .toList();
            var scales = answers.stream()
                    .filter(a -> a.getNumericValue() != null)
                    .map(a -> Map.of(
                            "question", qMap.get(a.getQuestionId()).getQuestionText(),
                            "value", a.getNumericValue()))
                    .toList();
            return Map.of("response", r, "texts", texts, "scales", scales);
        }).toList();

        model.addAttribute("property", property);
        model.addAttribute("feedbacks", withAnswers);
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
        c.setMaxAge(7 * 24 * 3600); // week
        response.addCookie(c);
        return "redirect:/housekeeper/" + code;
    }

    private String cookieValueFor(Property property) {
        // Simple deterministic token = SHA256(hash + id)
        String raw = property.getHousekeeperPinHash() + ":" + property.getId();
        return org.springframework.util.DigestUtils.md5DigestAsHex(raw.getBytes());
    }
}
```

Импорты: HttpServletResponse, Cookie, CookieValue, RequestParam, BCryptPasswordEncoder, ...

---

## 3. Property.java

Добавь поля:

```java
@Column(name = "housekeeper_code", nullable = false, unique = true)
private String housekeeperCode;

@Column(name = "housekeeper_pin_hash")
private String housekeeperPinHash;
```

---

## 4. PropertyRepository.java

```java
Optional<Property> findByHousekeeperCode(String code);
```

---

## 5. FeedbackResponseRepository — добавь методы

Смотри PATCH_FeedbackResponseRepository.txt

---

## 6. PricingEngine.java — множитель рейтинга

В поля:
```java
private final FeedbackAnalyticsService feedbackAnalytics;
```

В `runForProperty` после applyAiAdjustment и applyCompetitorGravity, перед items.add:
```java
double ratingMult = feedbackAnalytics.priceMultiplier(tenantId, property.getId());
if (ratingMult != 1.0) {
    finalPrice = finalPrice.multiply(BigDecimal.valueOf(ratingMult))
                           .setScale(0, RoundingMode.HALF_UP);
}
```

---

## 7. AiPricingAdvisor.java — блок в промпт

В поля:
```java
private final FeedbackAnalyticsService feedbackAnalytics;
```

В buildPrompt добавь `%s` для отзывов и в .format() параметр:
```java
feedbackAnalytics.buildPromptSection(tenantId, property.getId())
```

---

## 8. UI админа

**pages/pricing/index.html** или новая страница `/feedback-admin`:

- Список отзывов по объекту (title + рейтинг + текст)
- Переключатель "Показывать горничной" (toggle через POST /admin/feedback/{id}/toggle-housekeeper)
- В настройках объекта: поле для установки/смены PIN горничной

Мини-контроллер FeedbackAdminController:
```java
@PostMapping("/admin/feedback/{id}/toggle-housekeeper")
public String toggleHousekeeper(@PathVariable Long id) {
    var r = feedbackRepo.findById(id).orElseThrow();
    r.setShowToHousekeeper(!r.getShowToHousekeeper());
    feedbackRepo.save(r);
    return "redirect:/feedback-admin";
}

@PostMapping("/admin/property/{id}/set-housekeeper-pin")
public String setPin(@PathVariable Long id, @RequestParam String pin) {
    var p = propertyRepo.findById(id).orElseThrow();
    p.setHousekeeperPinHash(new BCryptPasswordEncoder().encode(pin));
    propertyRepo.save(p);
    return "redirect:/settings";
}
```

---

## 9. SecurityConfig — permitAll для новых URL

Проверь что путь `/housekeeper/**` и `/feedback/**` уже permitAll (по коду выше — они не под аутентификацией).
