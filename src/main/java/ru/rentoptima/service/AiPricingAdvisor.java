package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI-надстройка над алгоритмическими рекомендациями PricingEngine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPricingAdvisor {

    @Value("${anthropic.api.key:}")
    private String apiKey;

    @Value("${anthropic.api.url:https://api.anthropic.com/v1/messages}")
    private String apiUrl;

    @Value("${anthropic.model:claude-sonnet-4-5}")
    private String model;

    private final BookingRepository bookingRepo;
    private final BookingStatsService statsService;
    private final SettingsService settings;
    private final FeedbackAnalyticsService feedbackAnalytics;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Возвращает карту date -> multiplier (0.7..1.3) от AI.
     * При отсутствии ключа или ошибке возвращает пустую карту (fallback на алгоритм).
     */
    public Map<LocalDate, Double> getAdjustments(
            Property property,
            List<PricingEngine.PricingRecommendation> algoRecs,
            CompetitorService.CompetitorAnalysis competitorAnalysis
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("AI pricing: no API key, using pure algorithm");
            return Map.of();
        }

        try {
            String prompt = buildPrompt(property, algoRecs, competitorAnalysis);
            String response = callApi(prompt);
            Map<LocalDate, Double> adjustments = parseResponse(response);

            log.info("AI pricing: applied {} adjustments for property {}",
                    adjustments.size(), property.getName());
            return adjustments;

        } catch (Exception e) {
            log.warn("AI pricing failed, using algorithm: {}", e.getMessage());
            return Map.of();
        }
    }

    private String buildPrompt(
            Property property,
            List<PricingEngine.PricingRecommendation> recs,
            CompetitorService.CompetitorAnalysis competitorAnalysis
    ) {
        Long tenantId = property.getTenant().getId();

        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(7);

        List<Booking> recentBookings = bookingRepo
                .findActiveInRangeForTenant(tenantId, weekAgo, today.plusDays(60));

        Double bookingPace = statsService.getBookingPace(tenantId, today.plusDays(30));
        Double historicalOccupancy = statsService.getHistoricalOccupancy(tenantId);

        StringBuilder recsBlock = new StringBuilder();
        for (var r : recs) {
            if (r.status() == PricingEngine.DayStatus.BOOKED) continue;
            recsBlock.append(String.format("- %s (%s): %d₽, мин %dн, %s%n",
                    r.date(),
                    getDayName(r.date()),
                    r.recommendedPrice().intValue(),
                    r.recommendedMinStay(),
                    r.reason()));
        }

        StringBuilder bookingsBlock = new StringBuilder();
        int shown = 0;
        for (var b : recentBookings) {
            if (shown >= 5) break;
            bookingsBlock.append(String.format(
                    "- %s → %s (%dн, %s₽, %s)%n",
                    b.getCheckIn(),
                    b.getCheckOut(),
                    b.getNights() != null ? b.getNights() : 0,
                    b.getAmount() != null ? b.getAmount().toString() : "?",
                    b.getSource() != null ? b.getSource() : "?"
            ));
            shown++;
        }

        String competitorBlock = buildCompetitorBlock(competitorAnalysis);
        String ratingBlock = feedbackAnalytics.buildPromptSection(
                tenantId, property.getId());

        return String.format(
                """
                Ты — revenue manager для квартиры посуточной аренды в Выборге, ЛО.

                КОНТЕКСТ:
                - Объект: %s (%s)
                - Booking pace 30 дней: %.1f%%
                - Историческая заполняемость: %.1f%%
                - Последние 5 бронирований:
                %s

                РЕКОМЕНДАЦИИ АЛГОРИТМА (даты, цены, мин.сроки, причины):
                %s
                %s
                %s

                ТВОЯ ЗАДАЧА:
                Проанализируй сложившуюся ситуацию и скорректируй цены на конкретные даты,
                где ты считаешь алгоритм ошибается. Учитывай:
                - Праздники (1-4 ноября — праздничный кластер)
                - Сезонность
                - Скорость набора броней (booking pace)
                - Ценовое давление конкурентов если данные есть
                - Средний рейтинг гостей: высокий рейтинг допускает премию к цене, низкий требует скидки

                Отвечай в формате JSON без комментариев вне JSON:
                {
                  "adjustments": {
                    "2026-10-15": 1.05,
                    "2026-11-03": 1.15
                  },
                  "comment": "Краткое объяснение почему так решил"
                }

                Множители 0.75-1.50. Только даты которые нужно ИЗМЕНИТЬ.
                Пустой adjustments = алгоритм верен.
                """,
                property.getName(),
                property.getAddress() != null ? property.getAddress() : "",
                bookingPace != null ? bookingPace : 0.0,
                historicalOccupancy != null ? historicalOccupancy : 0.0,
                bookingsBlock,
                recsBlock,
                competitorBlock,
                ratingBlock
        );
    }

    private String buildCompetitorBlock(
            CompetitorService.CompetitorAnalysis analysis
    ) {
        if (analysis == null || analysis.competitorCount() == 0) {
            return "\n(Данные конкурентов пока не доступны)\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== КОНКУРЕНТНЫЙ АНАЛИЗ ===\n");
        sb.append(String.format("Конкурентов: %d%n", analysis.competitorCount()));

        if (analysis.avgPriceByDate() != null && !analysis.avgPriceByDate().isEmpty()) {
            sb.append("Средние цены конкурентов по датам:\n");
            analysis.avgPriceByDate().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .limit(15)
                    .forEach(e -> sb.append(String.format(
                            "  %s: %s₽%n",
                            e.getKey(),
                            e.getValue().toString())));
        }

        return sb.toString();
    }

    private String getDayName(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case MONDAY -> "Пн";
            case TUESDAY -> "Вт";
            case WEDNESDAY -> "Ср";
            case THURSDAY -> "Чт";
            case FRIDAY -> "Пт";
            case SATURDAY -> "Сб";
            case SUNDAY -> "Вс";
        };
    }

    private String callApi(String prompt) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", "2023-06-01");

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 2500);

        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", prompt);

        HttpEntity<String> request = new HttpEntity<>(
                objectMapper.writeValueAsString(body), headers);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, request, JsonNode.class);

        return response.getBody().path("content").get(0).path("text").asText();
    }

    private Map<LocalDate, Double> parseResponse(String jsonText) {
        try {
            String cleaned = jsonText.replaceAll("```json|```", "").trim();
            JsonNode root = objectMapper.readTree(cleaned);

            String comment = root.path("comment").asText("");
            if (!comment.isEmpty()) {
                log.info("AI pricing comment: {}", comment);
            }

            Map<LocalDate, Double> result = new HashMap<>();
            JsonNode adj = root.path("adjustments");
            if (adj.isObject()) {
                adj.fields().forEachRemaining(entry -> {
                    try {
                        LocalDate date = LocalDate.parse(entry.getKey());
                        double mult = entry.getValue().asDouble();
                        if (mult >= 0.7 && mult <= 1.5) {
                            result.put(date, mult);
                        } else {
                            log.warn("AI multiplier out of range: {} for {}",
                                    mult, date);
                        }
                    } catch (Exception e) {
                        log.warn("Cannot parse AI adjustment for {}: {}",
                                entry.getKey(), e.getMessage());
                    }
                });
            }
            return result;
        } catch (Exception e) {
            log.warn("AI pricing: cannot parse response: {}", e.getMessage());
            return Map.of();
        }
    }
}
