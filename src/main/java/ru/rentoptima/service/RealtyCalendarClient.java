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

@Slf4j
@Service
@RequiredArgsConstructor
public class RealtyCalendarClient {

    private final RestClient.Builder restClientBuilder;

    @Value("${app.realty-calendar.base-url:https://realtycalendar.ru}")
    private String baseUrl;

    @Value("${app.realty-calendar.username:}")
    private String username;

    @Value("${app.realty-calendar.password:}")
    private String password;

    @Value("${app.realty-calendar.locale:ru}")
    private String locale;

    private volatile String authToken;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * GET /v2/event_calendars/ — main endpoint for reading calendar state.
     * Returns:
     *   items[0].events[]         — actual bookings (status=booked)
     *   items[0].special_prices[] — price overrides, closed dates, restrictions
     */
    public JsonNode getCalendar(String rcObjectId, LocalDate beginDate, LocalDate endDate) {
        try {
            return client().get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/event_calendars/")
                            .queryParam("begin_date", beginDate.format(DATE_FMT))
                            .queryParam("end_date", endDate.format(DATE_FMT))
                            .queryParam("apartment_ids[]", rcObjectId)
                            .build())
                    .header("X-User-Token", token())
                    .header("X-Locale", locale)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.error("RC getCalendar failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * POST /v2/apartments/{id}/special_prices — write prices/restrictions.
     * Each item wraps values in {"actual": {"value": X}} format.
     */
    public void saveSpecialPrices(String rcObjectId, List<SpecialPrice> items) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules();

            var itemsList = items.stream().map(sp -> {
                var item = mapper.createObjectNode();
                item.put("date", sp.date().format(DATE_FMT));
                item.set("amount", wrapValue(mapper, sp.amount()));
                item.set("min_stay_through", wrapValue(mapper, sp.minStayThrough()));
                item.set("closed", wrapValue(mapper, false));
                item.set("closed_on_arrivial", wrapValue(mapper, false));
                item.set("closed_on_departure", wrapValue(mapper, false));
                var rates = mapper.createObjectNode();
                rates.put("use_rates_restrictions", false);
                rates.set("booking_rate_ids", mapper.createArrayNode());
                rates.set("ostrovok_rate_ids", mapper.createArrayNode());
                rates.set("expedia_rate_ids", mapper.createArrayNode());
                rates.set("bronevik_rate_ids", mapper.createArrayNode());
                rates.set("hotels101_rate_ids", mapper.createArrayNode());
                item.set("rates", rates);
                return item;
            }).toList();

            var root = mapper.createObjectNode();
            var arr = mapper.createArrayNode();
            itemsList.forEach(arr::add);
            root.set("items", arr);

            String json = mapper.writeValueAsString(root);
            log.debug("RC POST special_prices: {}", json.substring(0, Math.min(500, json.length())));

            client().post()
                    .uri("/v2/apartments/{id}/special_prices", rcObjectId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-User-Token", token())
                    .header("X-Locale", locale)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();

            log.info("RC POST special_prices SUCCESS: {} items for {}", items.size(), rcObjectId);
        } catch (Exception e) {
            log.error("RC POST special_prices FAILED for {}: {}", rcObjectId, e.getMessage());
            // Reset token on auth errors
            if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("403"))) {
                authToken = null;
            }
            throw new RuntimeException(e);
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode wrapValue(ObjectMapper mapper, Object value) {
        var wrapper = mapper.createObjectNode();
        var actual = mapper.createObjectNode();
        if (value instanceof BigDecimal bd) actual.put("value", bd.intValue());
        else if (value instanceof Integer i) actual.put("value", i);
        else if (value instanceof Boolean b) actual.put("value", b);
        else if (value instanceof String s) actual.put("value", s);
        else actual.putNull("value");
        wrapper.set("actual", actual);
        return wrapper;
    }

    private RestClient client() {
        return restClientBuilder.baseUrl(baseUrl).build();
    }

    private String token() {
        if (authToken != null) return authToken;
        synchronized (this) {
            if (authToken != null) return authToken;
            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                throw new IllegalStateException("RC_USERNAME и RC_PASSWORD не настроены");
            }
            JsonNode response = client().post()
                    .uri("/v2/sign_in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Locale", locale)
                    .body(Map.of("username", username, "password", password))
                    .retrieve()
                    .body(JsonNode.class);
            String received = response == null ? null : response.path("auth_token").asText(null);
            if (!StringUtils.hasText(received)) {
                throw new IllegalStateException("RealtyCalendar не вернул auth_token");
            }
            authToken = received;
            log.info("RC auth token obtained");
            return authToken;
        }
    }

    public record SpecialPrice(LocalDate date, BigDecimal amount, Integer minStayThrough) {}
}
