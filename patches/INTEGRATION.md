# Патчи для AI comments на дашборде

## 1. AiPricingAdvisor.java

**Меняем сигнатуру getAdjustments** — принимает tenantId и propertyId для сохранения комментария.

**В поля класса добавь:**
```java
    private final ru.rentoptima.repository.AiCommentRepository aiCommentRepo;
```

**Найди метод `parseResponse` и замени** его на два метода:

Было:
```java
    private Map<LocalDate, Double> parseResponse(String json) {
        Map<LocalDate, Double> result = new HashMap<>();
        try {
            String cleaned = json.replaceAll("```json|```", "").trim();
            JsonNode root = objectMapper.readTree(cleaned);

            String comment = root.path("comment").asText("");
            if (!comment.isEmpty()) {
                log.info("AI pricing comment: {}", comment);
            }

            JsonNode adj = root.path("adjustments");
            ...
```

Стало:
```java
    private Map<LocalDate, Double> parseResponse(String json,
                                                  Long tenantId, Long propertyId) {
        Map<LocalDate, Double> result = new HashMap<>();
        try {
            String cleaned = json.replaceAll("```json|```", "").trim();
            JsonNode root = objectMapper.readTree(cleaned);

            String comment = root.path("comment").asText("");
            
            JsonNode adj = root.path("adjustments");
            if (adj.isObject()) {
                adj.fields().forEachRemaining(entry -> {
                    try {
                        LocalDate date = LocalDate.parse(entry.getKey());
                        double mult = entry.getValue().asDouble(1.0);
                        if (mult >= 0.75 && mult <= 1.30) {
                            result.put(date, mult);
                        }
                    } catch (Exception e) {
                        log.warn("Skip bad adjustment {}: {}",
                                entry.getKey(), e.getMessage());
                    }
                });
            }

            // Save comment to DB for dashboard
            if (!comment.isEmpty()) {
                log.info("AI pricing comment: {}", comment);
                try {
                    ru.rentoptima.entity.AiComment c = new ru.rentoptima.entity.AiComment();
                    c.setTenantId(tenantId);
                    c.setPropertyId(propertyId);
                    c.setComment(comment);
                    c.setAdjustmentsCount(result.size());
                    aiCommentRepo.save(c);
                } catch (Exception e) {
                    log.warn("Failed to save AI comment: {}", e.getMessage());
                }
            }

            log.info("AI pricing: {} adjustments applied", result.size());
        } catch (Exception e) {
            log.warn("AI pricing: cannot parse response: {}", e.getMessage());
        }
        return result;
    }
```

**В методе `getAdjustments` — обнови вызов** parseResponse:

Было:
```java
            String response = callApi(apiKey, prompt);
            return parseResponse(response);
```

Стало:
```java
            String response = callApi(apiKey, prompt);
            return parseResponse(response, tenantId, property.getId());
```

---

## 2. DashboardController.java

**В поля добавь:**
```java
    private final ru.rentoptima.repository.AiCommentRepository aiCommentRepo;
```

**В методе dashboard добавь перед return:**
```java
        var aiComments = aiCommentRepo.findByTenantIdOrderByCreatedAtDesc(
                tenantId, org.springframework.data.domain.PageRequest.of(0, 5));
        model.addAttribute("aiComments", aiComments);
```

---

## 3. src/main/resources/templates/pages/dashboard/index.html

Найди место где сейчас виден блок «Наблюдения системы» (learningSummary). После него вставь:

```html
<!-- AI comments history -->
<div class="card" th:if="${aiComments != null and !#lists.isEmpty(aiComments)}"
     style="margin-bottom: var(--sp-5);">
    <div class="card__header">
        <div class="card__title">Комментарии AI-помощника</div>
    </div>
    <div th:each="c : ${aiComments}"
         style="padding: var(--sp-3) 0; border-bottom: 1px solid var(--border-subtle);">
        <div style="display: flex; justify-content: space-between; align-items: baseline; margin-bottom: var(--sp-2);">
            <span style="font-family: var(--font-mono); color: var(--text-secondary); font-size: var(--text-sm);"
                  th:text="${#temporals.format(c.createdAt, 'dd.MM HH:mm')}"></span>
            <span class="tag tag--amber" th:if="${c.adjustmentsCount > 0}"
                  th:text="${c.adjustmentsCount} + ' изменений'"></span>
        </div>
        <p style="margin: 0; color: var(--text); line-height: 1.5;" th:text="${c.comment}"></p>
    </div>
</div>
```

Коммит:
```
feat: AI comments history on dashboard
```
