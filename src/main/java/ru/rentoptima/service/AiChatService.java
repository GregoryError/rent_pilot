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
import ru.rentoptima.repository.BookingRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final SettingsService settings;
    private final BookingStatsService statsService;
    private final BookingRepository bookingRepo;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    public ChatResponse chat(Long tenantId, String userMessage, List<Map<String, String>> history) {
        String apiKey = settings.getValue(tenantId, "anthropic_api_key");
        if (apiKey == null || apiKey.isBlank()) {
            return new ChatResponse("Ключ Anthropic API не настроен. Добавьте его в Настройки → Интеграции.", 0);
        }

        try {
            String systemPrompt = buildSystemPrompt(tenantId);
            JsonNode requestBody = buildRequest(systemPrompt, userMessage, history);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
            ResponseEntity<JsonNode> response = restTemplate.exchange(API_URL, HttpMethod.POST, entity, JsonNode.class);

            if (response.getBody() != null && response.getBody().has("content")) {
                JsonNode content = response.getBody().get("content");
                StringBuilder text = new StringBuilder();
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        text.append(block.path("text").asText());
                    }
                }
                int tokens = response.getBody().path("usage").path("output_tokens").asInt(0)
                        + response.getBody().path("usage").path("input_tokens").asInt(0);
                return new ChatResponse(text.toString(), tokens);
            }

            return new ChatResponse("Пустой ответ от API.", 0);
        } catch (Exception e) {
            log.error("Anthropic API error: {}", e.getMessage());
            String errorMsg = e.getMessage().contains("401")
                    ? "Неверный API-ключ. Проверьте в Настройки → Интеграции."
                    : "Ошибка API: " + e.getMessage();
            return new ChatResponse(errorMsg, 0);
        }
    }

    private String buildSystemPrompt(Long tenantId) {
        Map<String, String> s = settings.getSettingsMap(tenantId);
        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);
        LocalDate monthEnd = now.plusMonths(1).withDayOfMonth(1).minusDays(1);

        var kpi = statsService.getKpi(tenantId, monthStart, monthEnd);
        var pace = statsService.getBookingPace(tenantId);

        // Recent bookings
        List<Booking> recent = bookingRepo.findActiveInRange(tenantId, now.minusDays(30), now.plusDays(30));
        StringBuilder recentStr = new StringBuilder();
        for (Booking b : recent) {
            recentStr.append(String.format("  %s → %s | %s | %s | %s₽\n",
                    b.getCheckIn(), b.getCheckOut(), b.getGuestName(),
                    b.getSource(), b.getAmount()));
        }

        return String.format("""
                        Ты — AI-аналитик системы оптимизации посуточной аренды RentOptima.
                        Ты помогаешь хозяину квартиры в городе %s принимать решения по ценообразованию,
                        управлению бронированиями и улучшению бизнеса.
                                        
                        Отвечай на русском языке. Будь конкретен, давай цифры и рекомендации.
                        Если данных недостаточно — скажи об этом.
                                        
                        === ДАННЫЕ КВАРТИРЫ ===
                        Город: %s
                        Базовая цена будни: %s ₽
                        Базовая цена выходные: %s ₽
                        Стоимость уборки: %s ₽
                        Наценка площадок: %s%%
                        Режим автопилота: %s
                                        
                        === KPI ТЕКУЩИЙ МЕСЯЦ ===
                        Доход: %s ₽
                        Бронирований: %d
                        Выездов (уборок): %d
                        Средняя длительность: %s ночей
                        Заполняемость: %s%%
                        Расход на уборку: %s ₽
                        Net RevPAR: %s ₽
                                        
                        === BOOKING PACE (следующий месяц) ===
                        Месяц: %s
                        Текущая загрузка: %s%%
                        Историческая норма: %s%%
                        Статус: %s
                                        
                        === НЕДАВНИЕ БРОНИРОВАНИЯ (±30 дней) === private JsonNode buildRequest(String systemPrompt, String userMessage, List<Map<String, String>> history) {
                                                                                ObjectNode root = objectMapper.createObjectNode();
                                                                                root.put("model", "claude-sonnet-4-6");
                                                                                root.put("max_tokens", 2000);
                                                                                root.put("system", systemPrompt);
                                                                        
                                                                                ArrayNode messages = root.putArray("messages");
                                                                        
                                                                                // Add conversation history
                                                                                if (history != null) {
                                                                                    for (Map<String, String> msg : history) {
                                                                                        ObjectNode m = messages.addObject();
                                                                                        m.put("role", msg.get("role"));
                                                                                        m.put("content", msg.get("content"));
                                                                                    }
                                                                                }
                                                                        
                                                                                // Add current message
                                                                                ObjectNode userMsg = messages.addObject();
                                                                                userMsg.put("role", "user");
                                                                                userMsg.put("content", userMessage);
                                                                        
                                                                                return root;
                                                                            }
                        %s
                        """,
                s.getOrDefault("city", "Выборг"),
                s.getOrDefault("city", "Выборг"),
                s.getOrDefault("weekday_base_price", "3200"),
                s.getOrDefault("weekend_base_price", "4200"),
                s.getOrDefault("cleaning_cost", "1400"),
                s.getOrDefault("platform_markup_pct", "18"),
                s.getOrDefault("autopilot_mode", "OFF"),
                kpi.revenue(), kpi.bookings(), kpi.checkouts(),
                kpi.avgNights(), kpi.occupancy(),
                kpi.cleaningCost(), kpi.netRevPar(),
                pace.month(), pace.currentOccupancy(), pace.historicalAvg(), pace.status(),
                recentStr.toString()
        );
    }



    public record ChatResponse(String content, int tokens) {}
}
