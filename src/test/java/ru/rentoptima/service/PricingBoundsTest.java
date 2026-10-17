package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Пределы рекомендаций: из настроек или от цены квартиры")
class PricingBoundsTest {

    @Test
    @DisplayName("пусто — от 50% до 250% цены квартиры на день")
    void emptyIsRelative() {
        assertThat(PricingEngine.bounds(Map.of("min_price_floor", "", "max_price_ceiling", ""), 4000))
                .containsExactly(2000, 10000);
        assertThat(PricingEngine.bounds(Map.of(), 12000)).containsExactly(6000, 30000);
    }

    @Test
    @DisplayName("заданы — берутся из настроек; можно задать только одну границу")
    void configured() {
        assertThat(PricingEngine.bounds(Map.of("min_price_floor", "2500", "max_price_ceiling", "9000"), 4000))
                .containsExactly(2500, 9000);
        assertThat(PricingEngine.bounds(Map.of("max_price_ceiling", "5000"), 4000)).containsExactly(2000, 5000);
    }

    @Test
    @DisplayName("мусор и противоречие (минимум выше максимума) — пределы от цены квартиры")
    void invalid() {
        assertThat(PricingEngine.bounds(Map.of("min_price_floor", "abc", "max_price_ceiling", "-1"), 4000))
                .containsExactly(2000, 10000);
        assertThat(PricingEngine.bounds(Map.of("min_price_floor", "9000", "max_price_ceiling", "5000"), 4000))
                .containsExactly(2000, 10000);
    }
}
