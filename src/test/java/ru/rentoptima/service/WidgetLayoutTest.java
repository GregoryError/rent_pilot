package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Виджет бронирования: раскладка и оформление в config_json")
class WidgetLayoutTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode normalize(String json) throws Exception {
        return WidgetLayout.normalize(json == null ? null : JSON.readTree(json));
    }

    private static List<String> texts(JsonNode array) {
        List<String> list = new ArrayList<>();
        array.forEach(n -> list.add(n.asText()));
        return list;
    }

    @Test
    @DisplayName("пусто или мусор — настройки по умолчанию")
    void defaults() throws Exception {
        for (String raw : new String[] {null, "[]", "\"x\"", "{\"preset\":\"<script>\",\"theme\":{\"accent\":\"red\",\"radius\":999,\"font\":\"comic\"}}"}) {
            ObjectNode c = normalize(raw);
            assertThat(c.path("preset").asText()).isEqualTo("split");
            assertThat(c.path("theme").path("accent").asText()).isEqualTo("#0f766e");
            assertThat(c.path("theme").path("font").asText()).isEqualTo("system");
            assertThat(c.path("theme").path("radius").asInt()).isBetween(0, 24);
            assertThat(c.path("hidden")).isEmpty();
        }
    }

    @Test
    @DisplayName("скрыть можно только необязательные блоки; неизвестные и повторы отбрасываются")
    void hidden() throws Exception {
        ObjectNode c = normalize("{\"hidden\":[\"map\",\"form\",\"calendar\",\"map\",\"evil\",\"rules\"]}");

        assertThat(texts(c.path("hidden"))).containsExactly("map", "rules");
        assertThat(WidgetLayout.hidden(c, "map")).isTrue();
        assertThat(WidgetLayout.hidden(c, "form")).isFalse();
    }

    @Test
    @DisplayName("custom: не больше трёх колонок, блок стоит один раз, забытые блоки уходят в последнюю колонку")
    void custom() throws Exception {
        ObjectNode c = normalize("""
                {"preset":"custom","custom":{
                  "top":["gallery","nope"],
                  "cols":[["title","gallery"],["calendar","guests"],[],["summary","form"],["map"]],
                  "mobile":["form","calendar"]}}""");

        JsonNode custom = c.path("custom");
        assertThat(texts(custom.path("top"))).containsExactly("gallery");
        assertThat(custom.path("cols")).hasSize(3);
        assertThat(texts(custom.path("cols").get(0))).containsExactly("title");
        assertThat(texts(custom.path("cols").get(1))).containsExactly("calendar", "guests");
        // «map» из четвёртой колонки не потерялся: вместе с остальными он в последней
        assertThat(texts(custom.path("cols").get(2)))
                .startsWith("summary", "form").contains("map", "description", "amenities", "rules", "contacts");
        // На узком экране — сначала порядок хозяина, потом всё остальное
        assertThat(texts(custom.path("mobile"))).startsWith("form", "calendar").hasSize(WidgetLayout.BLOCKS.size());
    }

    @Test
    @DisplayName("в config_json остаются только известные ключи")
    void unknownKeysDropped() throws Exception {
        ObjectNode c = normalize("{\"preset\":\"vertical\",\"onload\":\"alert(1)\",\"theme\":{\"accent\":\"#FF5A5F\",\"css\":\"x\"}}");

        assertThat(c.fieldNames()).toIterable().containsExactlyInAnyOrder("preset", "hidden", "theme");
        assertThat(c.path("theme").fieldNames()).toIterable().containsExactlyInAnyOrder("accent", "radius", "font");
        assertThat(c.path("theme").path("accent").asText()).isEqualTo("#ff5a5f");
    }

    @Test
    @DisplayName("контраст по WCAG и предупреждение о цвете, сливающемся с фоном")
    void accentContrast() {
        assertThat(WidgetLayout.contrast("#000000", "#ffffff")).isBetween(20.9, 21.1);
        assertThat(WidgetLayout.contrast("#0f766e", "#ffffff")).isGreaterThan(4.5);

        assertThat(WidgetLayout.accentWarning("#0f766e", "light")).isNull();
        assertThat(WidgetLayout.accentWarning("#0f766e", "auto")).isNull();
        assertThat(WidgetLayout.accentWarning("#fff6c2", "light")).contains("светлый");
        assertThat(WidgetLayout.accentWarning("#fff6c2", "dark")).isNull();
        assertThat(WidgetLayout.accentWarning("#101014", "dark")).contains("тёмный");
        assertThat(WidgetLayout.accentWarning("#101014", "light")).isNull();
        assertThat(WidgetLayout.accentWarning("#101014", "auto")).contains("тёмной теме").contains("светлую");
    }

    @Test
    @DisplayName("удобства: по одному в строке, без пустых и повторов, не больше тридцати")
    void amenities() {
        assertThat(WidgetLayout.amenities(" Wi-Fi \n\nКухня\nWi-Fi\r\nПарковка")).containsExactly("Wi-Fi", "Кухня", "Парковка");
        assertThat(WidgetLayout.amenities(null)).isEmpty();
        assertThat(WidgetLayout.amenities("x".repeat(200))).singleElement().asString().hasSize(60);
        assertThat(WidgetLayout.amenities(String.join("\n", java.util.stream.IntStream.range(0, 50).mapToObj(i -> "a" + i).toList())))
                .hasSize(30);
    }

    @Test
    @DisplayName("ссылка на карту: только https и только картографические сервисы")
    void mapUrl() {
        assertThat(WidgetLayout.mapUrl("https://yandex.ru/maps/-/CDabc")).isNotNull();
        assertThat(WidgetLayout.mapUrl(" https://go.2gis.com/xyz ")).isEqualTo("https://go.2gis.com/xyz");
        assertThat(WidgetLayout.mapUrl("https://www.google.com/maps/place/x")).isNotNull();
        assertThat(WidgetLayout.mapUrl("http://yandex.ru/maps/-/CDabc")).isNull();
        assertThat(WidgetLayout.mapUrl("https://yandex.ru.evil.example/maps")).isNull();
        assertThat(WidgetLayout.mapUrl("https://evil.example/?yandex.ru")).isNull();
        assertThat(WidgetLayout.mapUrl("javascript:alert(1)")).isNull();
        assertThat(WidgetLayout.mapUrl("https://yandex.ru/maps/\"onclick=\"x")).isNull();
        assertThat(WidgetLayout.mapUrl("")).isNull();
    }
}
