package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Виджет бронирования: проверка заявки")
class WidgetBookingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private static String validate(LocalDate from, LocalDate to) {
        return WidgetBookingService.validateStay(from, to, TODAY, 2, 14, 180);
    }

    @Test
    @DisplayName("допустимые даты проходят, включая заезд сегодня и выезд в последний день окна")
    void validStay() {
        assertThat(validate(TODAY, TODAY.plusDays(2))).isNull();
        assertThat(validate(TODAY.plusDays(170), TODAY.plusDays(180))).isNull();
    }

    @Test
    @DisplayName("отклоняются прошлое, перевёрнутые даты, выход за окно и за пределы числа ночей")
    void invalidStay() {
        assertThat(validate(null, TODAY.plusDays(2))).isNotNull();
        assertThat(validate(TODAY.minusDays(1), TODAY.plusDays(2))).contains("прошла");
        assertThat(validate(TODAY.plusDays(3), TODAY.plusDays(3))).contains("позже");
        assertThat(validate(TODAY.plusDays(5), TODAY.plusDays(3))).contains("позже");
        assertThat(validate(TODAY.plusDays(175), TODAY.plusDays(181))).contains("180");
        assertThat(validate(TODAY, TODAY.plusDays(1))).contains("Минимальный");
        assertThat(validate(TODAY, TODAY.plusDays(15))).contains("Максимальный");
    }

    @Test
    @DisplayName("телефон приводится к цифрам, мусор отбрасывается")
    void normalizePhone() {
        assertThat(WidgetBookingService.normalizePhone("+7 (900) 123-45-67")).isEqualTo("+79001234567");
        assertThat(WidgetBookingService.normalizePhone("8 900 123 45 67")).isEqualTo("89001234567");
        assertThat(WidgetBookingService.normalizePhone("12345")).isNull();
        assertThat(WidgetBookingService.normalizePhone("позвоните мне")).isNull();
        assertThat(WidgetBookingService.normalizePhone(null)).isNull();
    }

    @Test
    @DisplayName("лимит запросов: сверх лимита отказ, после окна счётчик обнуляется")
    void rateLimiter() {
        PublicRateLimiter limiter = new PublicRateLimiter();
        assertThat(limiter.allow("ip", 2, 1000, 0)).isTrue();
        assertThat(limiter.allow("ip", 2, 1000, 10)).isTrue();
        assertThat(limiter.allow("ip", 2, 1000, 20)).isFalse();
        assertThat(limiter.allow("other", 2, 1000, 20)).isTrue();
        assertThat(limiter.allow("ip", 2, 1000, 1000)).isTrue();
    }
}
