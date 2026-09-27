package ru.rentoptima.service;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class RealtyCalendarClient {

    private final RestClient.Builder restClientBuilder;
    private final SettingsService settings;

    @Value("${app.realty-calendar.base-url:https://realtycalendar.ru}")
    private String baseUrl;

    @Value("${app.realty-calendar.locale:ru}")
    private String locale;

    /** Токены per-tenant. Ключ = tenantId. */
    private final Map<Long, String> tokenCache = new ConcurrentHashMap<>();

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public JsonNode getSpecialPrices(
            Long tenantId,
            String rcObjectId,
            LocalDate beginDate,
            LocalDate endDate
    ) {
        try {
            log.debug(
                    "RC GET special_prices: object={}, {} - {}",
                    rcObjectId,
                    beginDate,
                    endDate
            );

            return client().get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/apartments/{id}/special_prices")
                            .queryParam("begin_date", beginDate.format(DATE_FMT))
                            .queryParam("end_date", endDate.format(DATE_FMT))
                            .build(rcObjectId))
                    .header("X-User-Token", token(tenantId))
                    .header("X-Locale", locale)
                    .retrieve()
                    .body(JsonNode.class);

        } catch (Exception e) {
            log.error("RC GET special_prices FAILED for {}: {}", rcObjectId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public JsonNode getEventCalendars(
            Long tenantId,
            String rcObjectId,
            LocalDate beginDate,
            LocalDate endDate
    ) {
        try {
            return client().get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/event_calendars/")
                            .queryParam("begin_date", beginDate.format(DATE_FMT))
                            .queryParam("end_date", endDate.format(DATE_FMT))
                            .queryParam("statuses[]", "booked")
                            .queryParam("statuses[]", "request")
                            .queryParam("apartment_ids", rcObjectId)
                            .build())
                    .header("X-User-Token", token(tenantId))
                    .header("X-Locale", locale)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("RC event_calendars failed: {}", e.getMessage());
            return null;
        }
    }

    public void saveSpecialPrices(
            Long tenantId,
            String rcObjectId,
            List<SpecialPrice> items
    ) {
        if (items == null || items.isEmpty()) return;

        try {
            ObjectMapper mapper = new ObjectMapper();
            var root = mapper.createObjectNode();
            var itemsArray = mapper.createArrayNode();

            for (SpecialPrice sp : items) {
                var item = mapper.createObjectNode();
                item.put("date", sp.date().format(DATE_FMT));

                var amount = mapper.createObjectNode();
                amount.put("value", toInt(sp.amount()));
                amount.put("source", "special_price");
                item.set("amount", amount);

                var minStay = mapper.createObjectNode();
                minStay.put("value", sp.minStayThrough() != null ? sp.minStayThrough() : 1);
                minStay.put("source", "special_price");
                item.set("min_stay_through", minStay);

                var closed = mapper.createObjectNode();
                closed.put("value", "no");
                closed.put("source", "default");
                item.set("closed", closed);

                var closedOnArrival = mapper.createObjectNode();
                closedOnArrival.put("value", "no");
                closedOnArrival.put("source", "default");
                item.set("closed_on_arrivial", closedOnArrival);

                var closedOnDeparture = mapper.createObjectNode();
                closedOnDeparture.put("value", "no");
                closedOnDeparture.put("source", "default");
                item.set("closed_on_departure", closedOnDeparture);

                var rates = mapper.createObjectNode();
                rates.put("use_rates_restrictions", false);
                item.set("rates", rates);

                var pricingRulesExcluded = mapper.createObjectNode();
                pricingRulesExcluded.put("value", "no");
                pricingRulesExcluded.put("source", "special_price");
                item.set("pricing_rules_excluded", pricingRulesExcluded);

                itemsArray.add(item);
            }

            root.set("items", itemsArray);
            String json = mapper.writeValueAsString(root);

            log.info("RC POST special_prices: object={}, items={}", rcObjectId, items.size());

            client().post()
                    .uri("/v2/apartments/{id}/special_prices", rcObjectId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-User-Token", token(tenantId))
                    .header("X-Locale", locale)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();

            log.info("RC POST special_prices SUCCESS: {} items for {}", items.size(), rcObjectId);

        } catch (Exception e) {
            log.error("RC POST special_prices FAILED for {}: {}", rcObjectId, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /** Инвалидирует токен для tenant (например при ошибке 401 или смене пароля). */
    public void invalidateToken(Long tenantId) {
        tokenCache.remove(tenantId);
    }

    /** Проверяет что для tenant заданы креды и токен получается. */
    public boolean testConnection(Long tenantId) {
        try {
            tokenCache.remove(tenantId); // force fresh login
            String t = token(tenantId);
            return StringUtils.hasText(t);
        } catch (Exception e) {
            log.warn("RC test connection failed for tenant {}: {}", tenantId, e.getMessage());
            return false;
        }
    }

    private int toInt(BigDecimal value) {
        return value == null ? 0 : value.intValue();
    }

    private RestClient client() {
        return restClientBuilder.baseUrl(baseUrl).build();
    }

    private String token(Long tenantId) {
        String existing = tokenCache.get(tenantId);
        if (existing != null) return existing;

        synchronized (this) {
            existing = tokenCache.get(tenantId);
            if (existing != null) return existing;

            String username = settings.getValue(tenantId, "rc_username");
            String password = settings.getEncryptedValue(tenantId, "rc_password");

            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                throw new IllegalStateException(
                        "RC credentials not configured for tenant " + tenantId);
            }

            JsonNode response = client().post()
                    .uri("/v2/sign_in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Locale", locale)
                    .body(Map.of("username", username, "password", password))
                    .retrieve()
                    .body(JsonNode.class);

            String receivedToken = response == null
                    ? null : response.path("auth_token").asText(null);

            if (!StringUtils.hasText(receivedToken)) {
                throw new IllegalStateException("RealtyCalendar не вернул auth_token");
            }

            tokenCache.put(tenantId, receivedToken);
            log.info("RC auth token obtained for tenant {}", tenantId);
            return receivedToken;
        }
    }

    public record SpecialPrice(
            LocalDate date,
            BigDecimal amount,
            Integer minStayThrough
    ) {}
}