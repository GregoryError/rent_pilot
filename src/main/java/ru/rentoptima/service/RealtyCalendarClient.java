package ru.rentoptima.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
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

    public JsonNode getSpecialPrices(String rcObjectId, LocalDate beginDate, LocalDate endDate) {
        return client().get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/apartments/{id}/special_prices")
                        .queryParam("begin_date", beginDate)
                        .queryParam("end_date", endDate)
                        .build(rcObjectId))
                .header("X-User-Token", token())
                .header("X-Locale", locale)
                .retrieve()
                .body(JsonNode.class);
    }

    public void saveSpecialPrices(String rcObjectId, List<SpecialPrice> items) {
        client().post()
                .uri("/v2/apartments/{id}/special_prices", rcObjectId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Token", token())
                .header("X-Locale", locale)
                .body(Map.of("items", items))
                .retrieve()
                .toBodilessEntity();
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
            LocalDate date,
            BigDecimal amount,
            @JsonProperty("min_stay_through") Integer minStayThrough,
            Boolean closed,
            @JsonProperty("closed_on_arrivial") Boolean closedOnArrival,
            @JsonProperty("closed_on_departure") Boolean closedOnDeparture,
            Map<String, List<Long>> rates
    ) { }
}
