package ru.rentoptima.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.List;
import java.util.Map;

/**
 * Private API used by the RealtyCalendar chessmate frontend.
 *
 * The endpoint contract was captured from the currently deployed chessmate
 * frontend. It is intentionally kept behind this server-side adapter: browser
 * tokens and RealtyCalendar credentials never reach a RentOptima user browser.
 */

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

    public JsonNode getSpecialPrices(
            String rcObjectId,
            LocalDate beginDate,
            LocalDate endDate
    ) {
        log.info(
                "RC GET special_prices: objectId={}, beginDate={}, endDate={}",
                rcObjectId, beginDate, endDate
        );

        JsonNode response = client().get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/apartments/{id}/special_prices")
                        .queryParam("begin_date", beginDate)
                        .queryParam("end_date", endDate)
                        .build(rcObjectId))
                .header("X-User-Token", token())
                .header("X-Locale", locale)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            log.warn("RC GET special_prices: response is NULL");
        } else {
            try {
                log.info(
                        "RC GET special_prices RESPONSE:\n{}",
                        new ObjectMapper()
                                .writerWithDefaultPrettyPrinter()
                                .writeValueAsString(response)
                );
            } catch (Exception e) {
                log.warn(
                        "RC GET special_prices: failed to pretty-print response: {}",
                        e.getMessage()
                );
                log.info("RC GET special_prices RESPONSE RAW: {}", response);
            }
        }

        return response;
    }

    public void saveSpecialPrices(
            String rcObjectId,
            List<SpecialPrice> items
    ) {
        Map<String, Object> body = Map.of("items", items);

        log.info(
                "RC POST special_prices: objectId={}, items={}",
                rcObjectId,
                items.size()
        );

        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules();

            log.info(
                    "RC POST special_prices REQUEST JSON:\n{}",
                    mapper.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(body)
            );
        } catch (Exception e) {
            log.warn(
                    "RC POST special_prices: failed to serialize request: {}",
                    e.getMessage()
            );
            log.info("RC POST special_prices REQUEST OBJECT: {}", body);
        }

        try {
            client().post()
                    .uri("/v2/apartments/{id}/special_prices", rcObjectId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-User-Token", token())
                    .header("X-Locale", locale)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info(
                    "RC POST special_prices SUCCESS: objectId={}, items={}",
                    rcObjectId,
                    items.size()
            );

        } catch (Exception e) {
            log.error(
                    "RC POST special_prices FAILED: objectId={}, items={}, error={}",
                    rcObjectId,
                    items.size(),
                    e.getMessage(),
                    e
            );

            throw e;
        }
    }

    private RestClient client() {
        return restClientBuilder.baseUrl(baseUrl).build();
    }

    private String token() {
        String existing = authToken;
        if (existing != null) return existing;

        synchronized (this) {
            if (authToken != null) return authToken;
            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                throw new IllegalStateException("Не настроены RC_USERNAME и RC_PASSWORD для интеграции с RealtyCalendar");
            }

            JsonNode response = client().post()
                    .uri("/v2/sign_in")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Locale", locale)
                    .body(new SignInRequest(username, password))
                    .retrieve()
                    .body(JsonNode.class);

            String receivedToken = response == null ? null : response.path("auth_token").asText(null);
            if (!StringUtils.hasText(receivedToken)) {
                throw new IllegalStateException("RealtyCalendar не вернул токен авторизации");
            }
            authToken = receivedToken;
            return receivedToken;
        }
    }

    private record SignInRequest(String username, String password) { }

    public record SpecialPrice(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate date,

            ValueWrapper amount,

            @JsonProperty("min_stay_through")
            ValueWrapper minStayThrough,

            ValueWrapper closed,

            @JsonProperty("closed_on_arrivial")
            ValueWrapper closedOnArrival,

            @JsonProperty("closed_on_departure")
            ValueWrapper closedOnDeparture,

            Rates rates
    ) {}

    public record ValueWrapper(
            DiagnosticValue actual
    ) {}

    public record DiagnosticValue(
            Object value
    ) {}

    public record Rates(
            @JsonProperty("use_rates_restrictions")
            Boolean useRatesRestrictions,

            @JsonProperty("booking_rate_ids")
            List<Long> bookingRateIds,

            @JsonProperty("ostrovok_rate_ids")
            List<Long> ostrovokRateIds,

            @JsonProperty("expedia_rate_ids")
            List<Long> expediaRateIds,

            @JsonProperty("bronevik_rate_ids")
            List<Long> bronevikRateIds,

            @JsonProperty("hotels101_rate_ids")
            List<Long> hotels101RateIds
    ) {}
}
