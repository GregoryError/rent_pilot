package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.service.CompetitorService.CompetitorAnalysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI advisor: раз в цикл автопилота отправляет Claude сводку по объекту
 * и получает per-date корректировки цены.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPricingAdvisor {

    private final BookingRepository bookingRepo;
    private final SettingsService settings;
    private final ProductionCalendarService prodCalendar;
    private final ObjectMapper objectMapper;
    private final BookingStatsService bookingStatsService;
    private final FeedbackAnalyticsService feedbackAnalytics;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ru.rentoptima.repository.AiCommentRepository aiCommentRepo;
    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    public Map<LocalDate, Double> getAdjustments(
            Property property,
            List<PricingEngine.PricingRecommendation> recs,
            CompetitorAnalysis competitorAnalysis) {

        Long tenantId = property.getTenant().getId();
        String apiKey = settings.getValue(tenantId, "anthropic_api_key");

        if (apiKey == null || apiKey.isBlank()) {
            log.info("AI pricing skipped: no anthropic_api_key");
            return Map.of();
        }

        try {
            String prompt = buildPrompt(property, recs, tenantId, competitorAnalysis);
            String response = callApi(apiKey, prompt);
            return parseResponse(response, tenantId, property.getId());
        } catch (Exception e) {
            log.warn("AI pricing failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private String buildPrompt(Property property,
                                List<PricingEngine.PricingRecommendation> recs,
                                Long tenantId,
                                CompetitorAnalysis competitorAnalysis) {

        LocalDate today = LocalDate.now();
        LocalDate from = today;
        LocalDate to = today.plusDays(60);

        int weekday = settings.getIntValue(tenantId, "weekday_base_price", 3200);
        int weekend = settings.getIntValue(tenantId, "weekend_base_price", 4200);
        int floor = settings.getIntValue(tenantId, "min_price_floor", 2500);
        int ceil = settings.getIntValue(tenantId, "max_price_ceiling", 10000);
        int cleaning = settings.getIntValue(tenantId, "cleaning_cost", 1400);

        List<Booking> bookings = bookingRepo.findByTenantIdAndStatusAndCheckInBetween(
                tenantId, "BOOKED",
                today.minusDays(90), today.plusDays(90));

        StringBuilder recentBookings = new StringBuilder();
        int shown = 0;
        for (int i = bookings.size() - 1; i >= 0 && shown < 5; i--) {
            Booking b = bookings.get(i);
            recentBookings.append(String.format(
                    "- %s→%s, %d ночей, %s ₽/ночь, %s\n",
                    b.getCheckIn(), b.getCheckOut(), b.getNights(),
                    b.getAmount().divide(BigDecimal.valueOf(Math.max(1, b.getNights())),
                            0, java.math.RoundingMode.HALF_UP),
                    b.getSource() != null ? b.getSource() : "?"));
            shown++;
        }

        double historicalOccupancy;
        double bookingPace;
        String paceStatus;
        String paceMonth;
        try {
            var pace = bookingStatsService.getBookingPace(tenantId);
            historicalOccupancy = pace.historicalAvg();
            bookingPace = pace.currentOccupancy();
            paceStatus = pace.status();
            paceMonth = pace.month();
        } catch (Exception e) {
            log.warn("Cannot get booking pace: {}", e.getMessage());
            historicalOccupancy = 0;
            bookingPace = 0;
            paceStatus = "?";
            paceMonth = "?";
        }

        StringBuilder recsList = new StringBuilder();
        int recCount = 0;
        for (PricingEngine.PricingRecommendation r : recs) {
            if (recCount >= 60) break;
            recsList.append(String.format("%s (%s): %d ₽, min_stay=%d, %s\n",
                    r.date(),
                    r.date().getDayOfWeek().toString().substring(0, 2),
                    r.recommendedPrice().intValue(),
                    r.recommendedMinStay(),
                    r.reason()));
            recCount++;
        }

        String competitorBlock = "";
        if (competitorAnalysis != null && !competitorAnalysis.avgPriceByDate().isEmpty()) {
            StringBuilder cb = new StringBuilder();
            cb.append(String.format("Наблюдаем %d конкурентов. Средние цены по датам:\n",
                    competitorAnalysis.competitorCount()));
            int cCount = 0;
            for (var e : competitorAnalysis.avgPriceByDate().entrySet()) {
                if (cCount >= 30) break;
                cb.append(String.format("  %s: %s ₽\n",
                        e.getKey(), e.getValue().intValue()));
                cCount++;
            }
            competitorBlock = cb.toString();
        }

        String ratingBlock = "";
        try {
            ratingBlock = feedbackAnalytics.buildPromptSection(tenantId, property.getId());
        } catch (Exception e) {
            log.debug("Rating block generation failed: {}", e.getMessage());
        }

        return String.format("""
                Ты — эксперт по revenue management посуточной аренды в Выборге (Ленинградская область).
                Твоя задача — скорректировать цены, рекомендованные алгоритмом, с учётом
                контекста и рынка.

                === ОБЪЕКТ ===
                %s, %s
                Базовые цены: будни %d₽, выходные %d₽
                Ограничения: min %d₽, max %d₽, уборка %d₽

                === ИСТОРИЯ ===
                Средняя историческая заполняемость (12 мес): %.1f%%
                Текущая заполняемость на %s: %.1f%% (статус: %s)

                Последние 5 бронирований:
                %s

                === АЛГОРИТМИЧЕСКИЕ РЕКОМЕНДАЦИИ ===
                (первые %d дат, всего %d)
                %s

                === КОНКУРЕНТНЫЙ АНАЛИЗ ===
                %s

                %s

                === ТВОЯ ЗАДАЧА ===
                Учти сезонность: сентябрь-ноябрь = высокий сезон в Выборге, декабрь-февраль = праздники,
                март-апрель = межсезонье, май-июль = высокий сезон.
                Короткие бронирования нормальны в высокий сезон, длинные — в межсезонье.

                Не паникуй если booking pace низкий в межсезонье — это норма.
                Учитывай отзывы и рейтинг: низкий рейтинг → цены ближе к рынку, высокий → можно выше.

                Верни СТРОГО JSON, ничего лишнего:
                {
                  "comment": "1-2 предложения о твоих решениях",
                  "adjustments": {
                    "2026-09-20": 1.05,
                    "2026-09-21": 0.95
                  }
                }

                Только те даты которые действительно нужно скорректировать.
                Множитель ∈ [0.75, 1.30]. Учти диапазон [%d, %d]₽.
                """,
                property.getName(),
                property.getAddress() != null ? property.getAddress() : "",
                weekday, weekend, floor, ceil, cleaning,
                historicalOccupancy, paceMonth, bookingPace, paceStatus,
                recentBookings.toString(),
                recCount, recs.size(),
                recsList.toString(),
                competitorBlock,
                ratingBlock,
                floor, ceil
        );
    }

    private String callApi(String apiKey, String prompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", "claude-sonnet-4-6");
        root.put("max_tokens", 2500);

        ArrayNode messages = root.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", prompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        HttpEntity<String> entity = new HttpEntity<>(
                objectMapper.writeValueAsString(root), headers);
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                API_URL, HttpMethod.POST, entity, JsonNode.class);

        if (response.getBody() == null)
            throw new IllegalStateException("Empty API response");

        return response.getBody().get("content").get(0).path("text").asText();
    }

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
}
