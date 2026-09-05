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

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public JsonNode getSpecialPrices(
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

            JsonNode response = client().get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/apartments/{id}/special_prices")
                            .queryParam(
                                    "begin_date",
                                    beginDate.format(DATE_FMT)
                            )
                            .queryParam(
                                    "end_date",
                                    endDate.format(DATE_FMT)
                            )
                            .build(rcObjectId)
                    )
                    .header("X-User-Token", token())
                    .header("X-Locale", locale)
                    .retrieve()
                    .body(JsonNode.class);

            log.info(
                    "RC GET special_prices RESPONSE: {}",
                    response
            );

            return response;

        } catch (Exception e) {
            log.error(
                    "RC GET special_prices FAILED for {}: {}",
                    rcObjectId,
                    e.getMessage(),
                    e
            );

            throw new RuntimeException(e);
        }
    }

    /**
     * Создание/изменение special prices в RealtyCalendar.
     *
     * Формат payload повторяет запрос, который делает
     * веб-интерфейс RealtyCalendar.
     */
    public void saveSpecialPrices(
            String rcObjectId,
            List<SpecialPrice> items
    ) {
        if (items == null || items.isEmpty()) {
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();

            var root = mapper.createObjectNode();
            var itemsArray = mapper.createArrayNode();

            for (SpecialPrice sp : items) {

                var item = mapper.createObjectNode();

                // date
                item.put(
                        "date",
                        sp.date().format(DATE_FMT)
                );

                // amount
                var amount = mapper.createObjectNode();
                amount.put("value", toInt(sp.amount()));
                amount.put("source", "special_price");
                item.set("amount", amount);

                // min_stay_through
                var minStay = mapper.createObjectNode();
                minStay.put(
                        "value",
                        sp.minStayThrough() != null
                                ? sp.minStayThrough()
                                : 1
                );
                minStay.put("source", "default");
                item.set("min_stay_through", minStay);

                // closed
                var closed = mapper.createObjectNode();
                closed.put("value", "no");
                closed.put("source", "default");
                item.set("closed", closed);

                // closed_on_arrivial
                //
                // В RC именно такая опечатка:
                // "arrivial", а не "arrival".
                var closedOnArrival = mapper.createObjectNode();
                closedOnArrival.put("value", "no");
                closedOnArrival.put("source", "default");
                item.set(
                        "closed_on_arrivial",
                        closedOnArrival
                );

                // closed_on_departure
                var closedOnDeparture = mapper.createObjectNode();
                closedOnDeparture.put("value", "no");
                closedOnDeparture.put("source", "default");
                item.set(
                        "closed_on_departure",
                        closedOnDeparture
                );

                // rates
                var rates = mapper.createObjectNode();
                rates.put(
                        "use_rates_restrictions",
                        false
                );
                item.set("rates", rates);

                // pricing_rules_excluded
                var pricingRulesExcluded =
                        mapper.createObjectNode();

                pricingRulesExcluded.put(
                        "value",
                        "no"
                );
                pricingRulesExcluded.put(
                        "source",
                        "special_price"
                );

                item.set(
                        "pricing_rules_excluded",
                        pricingRulesExcluded
                );

                itemsArray.add(item);
            }

            root.set("items", itemsArray);

            String json = mapper.writeValueAsString(root);

            log.info(
                    "RC POST special_prices: object={}, items={}",
                    rcObjectId,
                    items.size()
            );

//            log.debug(
//                    "RC POST special_prices BODY: {}",
//                    json
//            );

            client().post()
                    .uri(
                            "/v2/apartments/{id}/special_prices",
                            rcObjectId
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-User-Token", token())
                    .header("X-Locale", locale)
                    .body(json)
                    .retrieve()
                    .toBodilessEntity();

            log.info(
                    "RC POST special_prices SUCCESS: {} items for {}",
                    items.size(),
                    rcObjectId
            );

        } catch (Exception e) {

            log.error(
                    "RC POST special_prices FAILED for {}: {}",
                    rcObjectId,
                    e.getMessage(),
                    e
            );

            throw new RuntimeException(e);
        }
    }

    private int toInt(BigDecimal value) {
        if (value == null) {
            return 0;
        }

        return value.intValue();
    }

    private RestClient client() {
        return restClientBuilder
                .baseUrl(baseUrl)
                .build();
    }

    private String token() {

        String existing = authToken;

        if (existing != null) {
            return existing;
        }

        synchronized (this) {

            if (authToken != null) {
                return authToken;
            }

            if (!StringUtils.hasText(username)
                    || !StringUtils.hasText(password)) {

                throw new IllegalStateException(
                        "RC_USERNAME и RC_PASSWORD не настроены"
                );
            }

            JsonNode response = client().post()
                    .uri("/v2/sign_in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Locale", locale)
                    .body(
                            Map.of(
                                    "username",
                                    username,
                                    "password",
                                    password
                            )
                    )
                    .retrieve()
                    .body(JsonNode.class);

            String receivedToken =
                    response == null
                            ? null
                            : response.path("auth_token")
                            .asText(null);

            if (!StringUtils.hasText(receivedToken)) {
                throw new IllegalStateException(
                        "RealtyCalendar не вернул auth_token"
                );
            }

            authToken = receivedToken;

            log.info(
                    "RC auth token obtained successfully"
            );

            return receivedToken;
        }
    }

    public record SpecialPrice(
            LocalDate date,
            BigDecimal amount,
            Integer minStayThrough
    ) {}
}