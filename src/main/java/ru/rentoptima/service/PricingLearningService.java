package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.PricingDecision;
import ru.rentoptima.entity.PricingInsight;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PricingDecisionRepository;
import ru.rentoptima.repository.PricingInsightRepository;
import ru.rentoptima.repository.PropertyRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Мини-обучение по логам ценообразования.
 *
 * Пишет snapshot решения только при изменении условий (price / min_stay / ai_multiplier).
 * По расписанию (weekly/biweekly/monthly) анализирует лог через AI, извлекает
 * паттерны, сохраняет в pricing_insights. Опционально очищает сырые decisions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingLearningService {

    private final PricingDecisionRepository decisionRepo;
    private final PricingInsightRepository insightRepo;
    private final PropertyRepository propertyRepo;
    private final BookingRepository bookingRepo;
    private final SettingsService settings;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    /**
     * Пишет snapshot решения, если состояние отличается от последнего.
     * Возвращает true если запись создана.
     */
    @Transactional
    public boolean logDecisionIfChanged(
            Long tenantId, Long propertyId, LocalDate targetDate,
            int price, int minStay, Double aiMultiplier,
            int daysAhead, Integer windowLen,
            boolean isWeekend, boolean isHoliday,
            Double bookingPace, Integer competitorAvgPrice
    ) {
        if (!isLearningEnabled(tenantId)) return false;

        var latest = decisionRepo.findLatest(tenantId, propertyId, targetDate);
        if (latest.isPresent()) {
            var l = latest.get();
            boolean unchanged =
                    Objects.equals(l.getPrice(), price) &&
                    Objects.equals(l.getMinStay(), minStay) &&
                    Objects.equals(round2(l.getAiMultiplier()), round2(aiMultiplier));
            if (unchanged) return false;
        }

        PricingDecision d = PricingDecision.builder()
                .tenantId(tenantId)
                .propertyId(propertyId)
                .targetDate(targetDate)
                .decidedAt(LocalDateTime.now())
                .price(price)
                .minStay(minStay)
                .aiMultiplier(aiMultiplier)
                .daysAhead(daysAhead)
                .windowLen(windowLen)
                .isWeekend(isWeekend)
                .isHoliday(isHoliday)
                .bookingPace(bookingPace)
                .competitorAvgPrice(competitorAvgPrice)
                .build();
        decisionRepo.save(d);
        return true;
    }

    /**
     * При новой брони помечаем все pending decisions на её даты.
     * Вызывается из webhook при status=BOOKED.
     */
    @Transactional
    public void attributeBooking(Long tenantId, Long propertyId, Booking booking) {
        LocalDate d = booking.getCheckIn();
        while (d.isBefore(booking.getCheckOut())) {
            var decisions = decisionRepo
                    .findByTenantIdAndPropertyIdAndTargetDateAndOutcomeIsNull(
                            tenantId, propertyId, d);
            for (var dec : decisions) {
                dec.setBookedAt(LocalDateTime.now());
                dec.setBookedAmount(booking.getAmount());
                long days = ChronoUnit.DAYS.between(
                        dec.getDecidedAt().toLocalDate(), LocalDateTime.now().toLocalDate());
                dec.setDaysToBooking((int) days);
                dec.setOutcome("BOOKED");
                decisionRepo.save(dec);
            }
            d = d.plusDays(1);
        }
    }

    /**
     * Плановый анализ. Крутится ежедневно, но запускает работу
     * только когда пришёл срок по настройке frequency.
     */
    @Scheduled(cron = "0 30 3 * * *")  // 03:30 UTC ежедневно
    public void scheduledAnalysis() {
        for (var tenantId : List.of(1L)) { // TODO multi-tenant
            if (!isLearningEnabled(tenantId)) continue;
            if (!isDueForAnalysis(tenantId)) continue;
            try {
                runAnalysis(tenantId);
            } catch (Exception e) {
                log.error("Learning analysis failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    /** Ручной триггер анализа (кнопка на странице настроек). */
    public String runAnalysisNow(Long tenantId) {
        try {
            return runAnalysis(tenantId);
        } catch (Exception e) {
            log.error("Manual analysis failed: {}", e.getMessage());
            return "Ошибка: " + e.getMessage();
        }
    }

    @Transactional
    private String runAnalysis(Long tenantId) throws Exception {
        // 1. Помечаем просроченные pending → MISSED
        int marked = decisionRepo.markMissed(tenantId, LocalDate.now());
        log.info("Learning: marked {} decisions as MISSED for tenant {}", marked, tenantId);

        // 2. Собираем decisions за последние 90 дней
        var decisions = decisionRepo.findForAnalysis(tenantId, LocalDateTime.now().minusDays(90));
        if (decisions.size() < 20) {
            log.info("Learning: only {} decisions, need at least 20 for analysis", decisions.size());
            return "Недостаточно данных (нужно ≥20 решений, есть " + decisions.size() + ")";
        }

        // 3. AI анализ
        String apiKey = settings.getValue(tenantId, "anthropic_api_key");
        if (apiKey == null || apiKey.isBlank()) {
            return "Нет API ключа Anthropic для анализа";
        }

        String prompt = buildAnalysisPrompt(decisions);
        String response = callApi(apiKey, prompt);
        int savedInsights = parseAndSaveInsights(tenantId, response);

        // 4. Опциональная очистка сырых данных
        boolean keepRaw = "true".equalsIgnoreCase(
                settings.getValue(tenantId, "keep_raw_decisions"));
        if (!keepRaw) {
            int cleaned = decisionRepo.deleteAllByTenant(tenantId);
            log.info("Learning: cleaned {} raw decisions", cleaned);
        }

        // 5. Отметка даты последнего анализа
        settings.updateSetting(tenantId, "learning_last_run",
                LocalDate.now().toString());

        return String.format("Проанализировано %d решений, извлечено %d выводов",
                decisions.size(), savedInsights);
    }

    private String buildAnalysisPrompt(List<PricingDecision> decisions) {
        StringBuilder sb = new StringBuilder();
        sb.append("Дата_решения | Целевая_дата | dAhead | Цена | Мин.срок | AI× | Weekend | Holiday | Окно | Pace | Исход | Дней_до_брони | Сумма_брони\n");
        for (var d : decisions) {
            sb.append(String.format("%s | %s | %d | %d | %d | %.2f | %s | %s | %s | %.1f | %s | %s | %s\n",
                    d.getDecidedAt().toLocalDate(),
                    d.getTargetDate(),
                    d.getDaysAhead(),
                    d.getPrice(),
                    d.getMinStay(),
                    d.getAiMultiplier() != null ? d.getAiMultiplier() : 1.0,
                    d.getIsWeekend() ? "Y" : "N",
                    d.getIsHoliday() ? "Y" : "N",
                    d.getWindowLen() != null ? d.getWindowLen().toString() : "",
                    d.getBookingPace() != null ? d.getBookingPace() : 0.0,
                    d.getOutcome() != null ? d.getOutcome() : "PENDING",
                    d.getDaysToBooking() != null ? d.getDaysToBooking().toString() : "",
                    d.getBookedAmount() != null ? d.getBookedAmount().toString() : ""
            ));
        }

        return String.format("""
                Ты — аналитик revenue management посуточной аренды.
                Проанализируй лог решений по ценам и найди устойчивые паттерны:
                какие сочетания цены/мин.срока/расстояния до даты давали брони,
                а какие оставляли даты пустыми.
                
                ЛОГ РЕШЕНИЙ (последние 90 дней):
                %s
                
                Верни СТРОГО JSON (без markdown):
                {
                  "insights": [
                    {
                      "pattern_key": "короткий_ключ_snake_case",
                      "condition": {"days_ahead_min": 14, "days_ahead_max": 30, "is_weekend": true},
                      "action": {"price_ratio_min": 0.90, "price_ratio_max": 0.95, "min_stay_max": 3},
                      "sample_size": 12,
                      "conversion_rate": 0.68,
                      "avg_days_to_booking": 8.5,
                      "confidence": 0.75,
                      "summary": "Скидки 5-10%% на выходные за 14-30 дней приводили к брони в 68%% случаев в среднем через 8-9 дней"
                    }
                  ],
                  "global_summary": "1-2 предложения, самый важный вывод для дашборда"
                }
                
                Если данных мало для конкретного паттерна — не выдумывай, лучше меньше паттернов но с реальным sample_size.
                confidence ставь пропорционально размеру выборки (sample>=20 → 0.75+, sample<10 → до 0.4).
                """, sb.toString());
    }

    private String callApi(String apiKey, String prompt) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", "claude-sonnet-4-6");
        root.put("max_tokens", 3000);

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

    @Transactional
    private int parseAndSaveInsights(Long tenantId, String json) throws Exception {
        String cleaned = json.replaceAll("```json|```", "").trim();
        JsonNode root = objectMapper.readTree(cleaned);
        JsonNode insights = root.path("insights");

        String globalSummary = root.path("global_summary").asText("");

        int saved = 0;
        if (insights.isArray()) {
            for (JsonNode ins : insights) {
                PricingInsight pi = PricingInsight.builder()
                        .tenantId(tenantId)
                        .patternKey(ins.path("pattern_key").asText("unknown"))
                        .conditionJson(ins.path("condition").toString())
                        .actionJson(ins.path("action").toString())
                        .sampleSize(ins.path("sample_size").asInt(0))
                        .conversionRate(ins.path("conversion_rate").asDouble(0))
                        .avgDaysToBooking(ins.path("avg_days_to_booking").asDouble(0))
                        .confidence(ins.path("confidence").asDouble(0))
                        .summaryText(ins.path("summary").asText(""))
                        .discoveredAt(LocalDateTime.now())
                        .lastConfirmedAt(LocalDateTime.now())
                        .build();
                insightRepo.save(pi);
                saved++;
            }
        }

        // Save global summary as special insight for dashboard
        if (!globalSummary.isBlank()) {
            PricingInsight summary = PricingInsight.builder()
                    .tenantId(tenantId)
                    .patternKey("summary")
                    .summaryText(globalSummary)
                    .sampleSize(0)
                    .discoveredAt(LocalDateTime.now())
                    .lastConfirmedAt(LocalDateTime.now())
                    .build();
            insightRepo.save(summary);
        }

        return saved;
    }

    /** Форматированный текст последнего summary для дашборда. */
    public String getLatestSummary(Long tenantId) {
        return insightRepo
                .findFirstByTenantIdAndPatternKeyOrderByDiscoveredAtDesc(tenantId, "summary")
                .map(PricingInsight::getSummaryText)
                .orElse(null);
    }

    /** Все выученные правила (без summary-строки). */
    public List<PricingInsight> getAllInsights(Long tenantId) {
        return insightRepo.findByTenantIdOrderByDiscoveredAtDesc(tenantId).stream()
                .filter(i -> !"summary".equals(i.getPatternKey()))
                .toList();
    }

    /** Строка для промпта AiPricingAdvisor. */
    public String buildInsightsForPrompt(Long tenantId) {
        var insights = getAllInsights(tenantId);
        if (insights.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("=== ИЗУЧЕННЫЕ ПАТТЕРНЫ (из истории решений) ===\n");
        for (var ins : insights) {
            if (ins.getConfidence() != null && ins.getConfidence() < 0.4) continue;
            sb.append(String.format("• [%s, conf=%.2f, n=%d] %s\n",
                    ins.getPatternKey(),
                    ins.getConfidence() != null ? ins.getConfidence() : 0.0,
                    ins.getSampleSize() != null ? ins.getSampleSize() : 0,
                    ins.getSummaryText()));
        }
        return sb.toString();
    }

    @Transactional
    public int forgetInsights(Long tenantId) {
        return insightRepo.deleteAllByTenant(tenantId);
    }

    @Transactional
    public int clearRawDecisions(Long tenantId) {
        return decisionRepo.deleteAllByTenant(tenantId);
    }

    public LearningStats getStats(Long tenantId) {
        return new LearningStats(
                decisionRepo.countByTenantId(tenantId),
                insightRepo.countByTenantId(tenantId),
                settings.getValue(tenantId, "learning_last_run"),
                isLearningEnabled(tenantId)
        );
    }

    public record LearningStats(long decisionsCount, long insightsCount,
                                 String lastRun, boolean enabled) {}

    private boolean isLearningEnabled(Long tenantId) {
        return "true".equalsIgnoreCase(
                settings.getValue(tenantId, "learning_enabled"));
    }

    private boolean isDueForAnalysis(Long tenantId) {
        String freq = settings.getValue(tenantId, "learning_analysis_frequency");
        if (freq == null) freq = "weekly";

        String lastRun = settings.getValue(tenantId, "learning_last_run");
        if (lastRun == null || lastRun.isBlank()) return true;

        LocalDate last;
        try { last = LocalDate.parse(lastRun); }
        catch (Exception e) { return true; }

        long daysSince = ChronoUnit.DAYS.between(last, LocalDate.now());
        return switch (freq) {
            case "biweekly" -> daysSince >= 14;
            case "monthly" -> daysSince >= 30;
            default -> daysSince >= 7; // weekly
        };
    }

    private Double round2(Double v) {
        if (v == null) return null;
        return Math.round(v * 100.0) / 100.0;
    }
}
