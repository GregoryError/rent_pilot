package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

    /**
     * Получить спецусловия календаря RC.
     *
     * В ответе находятся данные по датам:
     *
     * - amount
     * - min_stay_through
     * - closed
     * - closed_on_arrivial
     * - closed_on_departure
     * - rates
     *
     * Этот метод используется PricingEngine перед изменением цен.
     */
    public JsonNode getSpecialPrices(
            String rcObjectId,
            LocalDate beginDate,
            LocalDate endDate
    ) {

        log.debug(
                "RC GET special_prices: object={}, {} - {}",
                rcObjectId,
                beginDate,
                endDate
        );

        JsonNode response =
                client()
                        .get()
                        .uri(uriBuilder ->
                                uriBuilder
                                        .path(
                                                "/v2/apartments/{id}/special_prices"
                                        )
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
                        .header(
                                "X-User-Token",
                                token()
                        )
                        .header(
                                "X-Locale",
                                locale
                        )
                        .retrieve()
                        .body(JsonNode.class);

        if (response == null) {

            throw new IllegalStateException(
                    "RealtyCalendar returned empty response"
            );
        }

        log.debug(
                "RC GET special_prices SUCCESS: object={}, {} - {}",
                rcObjectId,
                beginDate,
                endDate
        );

        return response;
    }

    /**
     * Сохранить специальные цены.
     *
     * ВАЖНО:
     *
     * Этот метод НЕ меняет:
     *
     * - closed
     * - closed_on_arrivial
     * - closed_on_departure
     *
     * Они намеренно отсутствуют в POST.
     *
     * Поэтому ручное закрытие даты в RealtyCalendar
     * не будет снято автопилотом.
     */
    public void saveSpecialPrices(
            String rcObjectId,
            List<SpecialPrice> items
    ) {

        if (items == null || items.isEmpty()) {

            log.debug(
                    "RC POST special_prices: no items for {}",
                    rcObjectId
            );

            return;
        }

        try {

            ObjectMapper mapper =
                    new ObjectMapper();

            mapper.registerModule(
                    new JavaTimeModule()
            );

            /*
             * -----------------------------------------------------
             * Формируем items
             * -----------------------------------------------------
             */

            var itemsList =
                    items.stream()
                            .map(sp -> {

                                var item =
                                        mapper.createObjectNode();

                                /*
                                 * Дата.
                                 */
                                item.put(
                                        "date",
                                        sp.date().format(DATE_FMT)
                                );

                                /*
                                 * Цена.
                                 */
                                item.set(
                                        "amount",
                                        wrapValue(
                                                mapper,
                                                sp.amount()
                                        )
                                );

                                /*
                                 * Минимальный срок.
                                 */
                                item.set(
                                        "min_stay_through",
                                        wrapValue(
                                                mapper,
                                                sp.minStayThrough()
                                        )
                                );

                                /*
                                 * -------------------------------------------------
                                 * ВАЖНО:
                                 *
                                 * НЕ отправляем:
                                 *
                                 * closed
                                 * closed_on_arrivial
                                 * closed_on_departure
                                 *
                                 * Поэтому эти параметры существующего
                                 * спецусловия RC не перезаписываются.
                                 * -------------------------------------------------
                                 */

                                /*
                                 * Rates.
                                 *
                                 * Сохраняем существующий формат,
                                 * который уже принимает RC.
                                 */
                                var rates =
                                        mapper.createObjectNode();

                                rates.put(
                                        "use_rates_restrictions",
                                        false
                                );

                                rates.set(
                                        "booking_rate_ids",
                                        mapper.createArrayNode()
                                );

                                rates.set(
                                        "ostrovok_rate_ids",
                                        mapper.createArrayNode()
                                );

                                rates.set(
                                        "expedia_rate_ids",
                                        mapper.createArrayNode()
                                );

                                rates.set(
                                        "bronevik_rate_ids",
                                        mapper.createArrayNode()
                                );

                                rates.set(
                                        "hotels101_rate_ids",
                                        mapper.createArrayNode()
                                );

                                item.set(
                                        "rates",
                                        rates
                                );

                                return item;
                            })
                            .toList();

            /*
             * -----------------------------------------------------
             * Root JSON
             * -----------------------------------------------------
             */

            var root =
                    mapper.createObjectNode();

            var arr =
                    mapper.createArrayNode();

            itemsList.forEach(
                    arr::add
            );

            root.set(
                    "items",
                    arr
            );

            String json =
                    mapper.writeValueAsString(root);

            log.debug(
                    "RC POST special_prices: {}",
                    json.substring(
                            0,
                            Math.min(
                                    1000,
                                    json.length()
                            )
                    )
            );

            /*
             * -----------------------------------------------------
             * POST
             * -----------------------------------------------------
             */

            client()
                    .post()
                    .uri(
                            "/v2/apartments/{id}/special_prices",
                            rcObjectId
                    )
                    .contentType(
                            MediaType.APPLICATION_JSON
                    )
                    .header(
                            "X-User-Token",
                            token()
                    )
                    .header(
                            "X-Locale",
                            locale
                    )
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

    /**
     * Формат:
     *
     * {
     *   "actual": {
     *     "value": X
     *   }
     * }
     */
    private com.fasterxml.jackson.databind.node.ObjectNode wrapValue(
            ObjectMapper mapper,
            Object value
    ) {

        var wrapper =
                mapper.createObjectNode();

        var actual =
                mapper.createObjectNode();

        if (value instanceof BigDecimal bd) {

            actual.put(
                    "value",
                    bd.intValue()
            );

        } else if (value instanceof Integer i) {

            actual.put(
                    "value",
                    i
            );

        } else if (value instanceof Boolean b) {

            actual.put(
                    "value",
                    b
            );

        } else if (value instanceof String s) {

            actual.put(
                    "value",
                    s
            );

        } else if (value == null) {

            actual.putNull(
                    "value"
            );

        } else {

            actual.put(
                    "value",
                    value.toString()
            );
        }

        wrapper.set(
                "actual",
                actual
        );

        return wrapper;
    }

    /**
     * Создание RestClient.
     */
    private RestClient client() {

        return restClientBuilder
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Получение и кеширование токена RC.
     */
    private String token() {

        String existing =
                authToken;

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

            JsonNode response =
                    client()
                            .post()
                            .uri("/v2/sign_in")
                            .contentType(
                                    MediaType.APPLICATION_JSON
                            )
                            .header(
                                    "X-Locale",
                                    locale
                            )
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
                            : response
                            .path("auth_token")
                            .asText(null);

            if (!StringUtils.hasText(receivedToken)) {

                throw new IllegalStateException(
                        "RealtyCalendar не вернул auth_token"
                );
            }

            authToken =
                    receivedToken;

            log.info(
                    "RC auth token obtained successfully"
            );

            return receivedToken;
        }
    }

    /**
     * Данные для изменения цены.
     */
    public record SpecialPrice(

            LocalDate date,

            BigDecimal amount,

            Integer minStayThrough

    ) {
    }
}