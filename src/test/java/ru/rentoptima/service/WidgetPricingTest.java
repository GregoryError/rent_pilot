package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.entity.PromoCode;
import ru.rentoptima.service.WidgetPricing.NightPrice;
import ru.rentoptima.service.WidgetPricing.Quote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Виджет бронирования: расчёт суммы")
class WidgetPricingTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 2);

    private static List<NightPrice> nights(int count, int price) {
        List<NightPrice> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(new NightPrice(START.plusDays(i), BigDecimal.valueOf(price)));
        return list;
    }

    private static PromoCode promo(String type, int value) {
        PromoCode p = new PromoCode();
        p.setCode("LETO");
        p.setDiscountType(type);
        p.setDiscountValue(BigDecimal.valueOf(value));
        return p;
    }

    private static Quote quote(List<NightPrice> nights, int cleaning, int weekly, int monthly, PromoCode promo) {
        return WidgetPricing.quote(nights, BigDecimal.valueOf(cleaning), weekly, monthly, 0, promo);
    }

    @Test
    @DisplayName("разные цены по ночам складываются, уборка добавляется один раз")
    void nightsPlusCleaning() {
        List<NightPrice> nights = List.of(
                new NightPrice(START, BigDecimal.valueOf(4000)),
                new NightPrice(START.plusDays(1), BigDecimal.valueOf(4000)),
                new NightPrice(START.plusDays(2), BigDecimal.valueOf(5500)));
        Quote q = quote(nights, 1500, 10, 20, null);

        assertThat(q.nights()).isEqualTo(3);
        assertThat(q.accommodation()).isEqualByComparingTo("13500");
        assertThat(q.lengthPercent()).isZero();
        assertThat(q.total()).isEqualByComparingTo("15000");
    }

    @Test
    @DisplayName("от 7 ночей — недельная скидка, от 28 — месячная; не складываются")
    void lengthDiscount() {
        assertThat(quote(nights(6, 1000), 0, 10, 20, null).lengthDiscount()).isEqualByComparingTo("0");

        Quote week = quote(nights(7, 1000), 0, 10, 20, null);
        assertThat(week.lengthPercent()).isEqualTo(10);
        assertThat(week.total()).isEqualByComparingTo("6300");

        Quote month = quote(nights(28, 1000), 0, 10, 20, null);
        assertThat(month.lengthPercent()).isEqualTo(20);
        assertThat(month.total()).isEqualByComparingTo("22400");
    }

    @Test
    @DisplayName("месячная скидка не задана или меньше недельной — на длинное проживание действует недельная")
    void longStayFallsBackToWeekly() {
        assertThat(quote(nights(30, 1000), 0, 10, 0, null).lengthPercent()).isEqualTo(10);
        assertThat(quote(nights(30, 1000), 0, 15, 5, null).lengthPercent()).isEqualTo(15);
    }

    @Test
    @DisplayName("промокод-процент считается после скидки за длительность и не трогает уборку")
    void percentPromo() {
        Quote q = quote(nights(7, 1000), 2000, 10, 0, promo(PromoCode.TYPE_PERCENT, 50));

        assertThat(q.lengthDiscount()).isEqualByComparingTo("700");
        assertThat(q.promoDiscount()).isEqualByComparingTo("3150");
        assertThat(q.total()).isEqualByComparingTo("5150");
        assertThat(q.discountTotal()).isEqualByComparingTo("3850");
        assertThat(q.promoCode()).isEqualTo("LETO");
    }

    @Test
    @DisplayName("промокод на сумму больше стоимости проживания не уводит её в минус — остаётся уборка")
    void amountPromoIsCapped() {
        Quote q = quote(nights(2, 1000), 500, 0, 0, promo(PromoCode.TYPE_AMOUNT, 5000));

        assertThat(q.promoDiscount()).isEqualByComparingTo("2000");
        assertThat(q.total()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("предоплата — процент от итога, округляется до рубля")
    void prepayment() {
        Quote q = WidgetPricing.quote(nights(3, 3333), BigDecimal.ZERO, 0, 0, 30, null);

        assertThat(q.total()).isEqualByComparingTo("9999");
        assertThat(q.prepayment()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("цена задана не на все ночи — итога нет, число ночей есть")
    void incomplete() {
        List<NightPrice> nights = List.of(
                new NightPrice(START, BigDecimal.valueOf(4000)),
                new NightPrice(START.plusDays(1), null));
        Quote q = quote(nights, 1500, 0, 0, null);

        assertThat(q.complete()).isFalse();
        assertThat(q.total()).isNull();
        assertThat(q.nights()).isEqualTo(2);
    }

    @Test
    @DisplayName("разбивка сходится с итогом при копеечных ценах")
    void roundedLinesAddUp() {
        List<NightPrice> nights = List.of(
                new NightPrice(START, new BigDecimal("3333.40")),
                new NightPrice(START.plusDays(1), new BigDecimal("3333.40")),
                new NightPrice(START.plusDays(2), new BigDecimal("3333.40")));
        Quote q = quote(nights, 0, 0, 0, null);

        assertThat(q.accommodation()).isEqualByComparingTo("9999");
        assertThat(q.total()).isEqualByComparingTo(q.accommodation().subtract(q.discountTotal()).add(q.cleaningFee()));
    }
}
