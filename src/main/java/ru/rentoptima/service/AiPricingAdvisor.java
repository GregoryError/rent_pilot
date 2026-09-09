package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;

import java.time.LocalDate;
import java.util.*;

/**
 * AI pricing advisor — queries Anthropic API to adjust deterministic
 * pricing recommendations based on external context.
 *
 * Fallback: if API unavailable, returns empty adjustments (algorithm runs as-is).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPricingAdvisor {

    private final SettingsService settings;
    private final BookingRepository bookingRepo;
    private final BookingStatsService statsService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    /**
     * Returns map: date → price multiplier.
     * Empty map = no AI adjustments, use algorithm as-is.
     */
    public Map<LocalDate, Double> getAdjustments(
            Property property,
            List<PricingEngine.PricingRecommendation> recs) {

        Long tenantId = property.getTenant().getId();
        String apiKey = settings.getValue(tenantId, "anthropic_api_key");

        if (apiKey == null || apiKey.isBlank()) {
            log.debug("AI pricing: no API key, skipping");
            return Map.of();
        }

        // Only send FREE/GAP days — no point adjusting booked ones
        List<PricingEngine.PricingRecommendation> freeDays = recs.stream()
                .filter(r -> r.status() != PricingEngine.DayStatus.BOOKED)
                .toList();

        if (freeDays.isEmpty()) return Map.of();

        try {
            String prompt = buildPrompt(property, tenantId, freeDays);
            String response = callApi(apiKey, prompt);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("AI pricing advisor error (fallback to algorithm): {}", e.getMessage());
            return Map.of(); // Graceful fallback
        }
    }

    private String buildPrompt(Property property, Long tenantId,
                               List<PricingEngine.PricingRecommendation> recs) {

        Map<String, String> s = settings.getSettingsMap(tenantId);
        LocalDate now = LocalDate.now();

        // Booking pace
        var pace = statsService.getBookingPace(tenantId);

        // Historical: same period last year
        LocalDate histFrom = now.minusYears(1);
        LocalDate histTo = now.minusYears(1).plusDays(90);
        long histNights = Optional.ofNullable(
                bookingRepo.sumNightsInRange(tenantId, histFrom, histTo)).orElse(0L);
        long histDays = 90;
        double histOccupancy = (double) histNights / histDays * 100;

        // Recent bookings context (last 5 before today)
        List<Booking> recent = bookingRepo.findActiveInRangeForTenant(
                tenantId, now.minusDays(60), now);
        StringBuilder recentStr = new StringBuilder();
        recent.stream().sorted(Comparator.comparing(Booking::getCheckIn).reversed())
                .limit(5)
                .forEach(b -> recentStr.append(String.format(
                        "  %s–%s, %s н., %s, %s₽\n",
                        b.getCheckIn(), b.getCheckOut(), b.getNights(),
                        b.getSource() != null ? b.getSource() : "ручная",
                        b.getAmount())));

        // Algorithm recommendations summary
        StringBuilder recsStr = new StringBuilder();
        recsStr.append("Дата | Статус | Цена алг. | Мин.срок | Причина\n");
        recs.stream().limit(45).forEach(r -> recsStr.append(String.format(
                "%s | %s | %s₽ | %d н. | %s\n",
                r.date(), r.status(), r.recommendedPrice(),
                r.recommendedMinStay(), r.reason())));

        return String.format("""
                Ты — эксперт по revenue management посуточной аренды квартир.
                
                Объект: %s, г. %s
                Базовые цены: будни %s₽, выходные %s₽
                Наценка площадок: %s%%
                Стоимость уборки: %s₽
                
                === BOOKING PACE (следующий месяц) ===
                Текущая загрузка: %.1f%%
                Историческая норма: %.1f%%
                Статус: %s
                
                === ИСТОРИЧЕСКИЕ ДАННЫЕ (тот же период год назад, если есть) ===
                Заполняемость: %.1f%%
                
                === НЕДАВНИЕ БРОНИ (последние 5) ===
                %s
                
                === РЕКОМЕНДАЦИИ АЛГОРИТМА (ближайшие 45 дней) ===
                %s
                
                Твоя задача: проанализируй данные и предложи ТОЛЬКО корректировки к ценам
                алгоритма там, где видишь весомую причину (событие в городе, аномальный
                спрос, слабый booking pace, исторический паттерн).
                
                Не корректируй без причины. Множитель 1.0 = без изменений.
                Допустимый диапазон множителей: 0.70 – 1.70.
                
                Отвечай СТРОГО в формате JSON (без markdown, без пояснений снаружи):
                {
                  "adjustments": [
                    {"date": "YYYY-MM-DD", "price_multiplier": 1.0, "reason": "..."},
                    ...
                  ],
                  "global_comment": "краткий общий вывод"
                }
                
                Если корректировок нет — верни {"adjustments": [], "global_comment": "..."}
                """,
                property.getName(),
                s.getOrDefault("city", "Выборг"),
                s.getOrDefault("weekday_base_price", "3200"),
                s.getOrDefault("weekend_base_price", "4200"),
                s.getOrDefault("platform_markup_pct", "18"),
                s.getOrDefault("cleaning_cost", "1400"),
                pace.currentOccupancy(), pace.historicalAvg(), pace.status(),
                histOccupancy,
                recentStr,
                recsStr
        );
    }

    private String callApi(String apiKey, String prompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", "claude-sonnet-4-6");
        root.put("max_tokens", 1500);

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

        if (response.getBody() == null) throw new IllegalStateException("Empty API response");
        return response.getBody().get("content").get(0).path("text").asText();
    }

    private Map<LocalDate, Double> parseResponse(String json) {
        Map<LocalDate, Double> result = new HashMap<>();
        try {
            // Strip markdown if present
            String cleaned = json.replaceAll("```json|```", "").trim();
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode adjustments = root.path("adjustments");

            String globalComment = root.path("global_comment").asText("");
            if (!globalComment.isBlank()) {
                log.info("AI pricing comment: {}", globalComment);
            }

            if (!adjustments.isArray()) return result;

            for (JsonNode adj : adjustments) {
                String dateStr = adj.path("date").asText(null);
                double multiplier = adj.path("price_multiplier").asDouble(1.0);
                String reason = adj.path("reason").asText("");

                if (dateStr == null) continue;

                // Validate multiplier range
                if (multiplier < 0.75 || multiplier > 1.50) {
                    log.warn("AI pricing: invalid multiplier {} for {}, clamping", multiplier, dateStr);
                    multiplier = Math.max(0.75, Math.min(1.50, multiplier));
                }

                if (Math.abs(multiplier - 1.0) > 0.01) { // Only store non-trivial adjustments
                    result.put(LocalDate.parse(dateStr), multiplier);
                    log.info("AI pricing adjustment: {} × {:.2f} — {}", dateStr, multiplier, reason);
                }
            }

            log.info("AI pricing: {} adjustments applied", result.size());
        } catch (Exception e) {
            log.warn("AI pricing: cannot parse response: {}", e.getMessage());
        }
        return result;
    }
}
