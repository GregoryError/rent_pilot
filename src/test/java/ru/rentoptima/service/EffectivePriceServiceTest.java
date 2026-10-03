package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.entity.UnitType;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EffectivePriceService: статическая логика резолва")
class EffectivePriceServiceTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);

    private UnitType unitType(BigDecimal base, BigDecimal weekend) {
        UnitType ut = new UnitType();
        ut.setBasePrice(base);
        ut.setWeekendPrice(weekend);
        return ut;
    }

    @Test
    @DisplayName("override имеет наивысший приоритет над всем")
    void overrideWinsOverEverything() {
        UnitType ut = unitType(new BigDecimal("3000"), new BigDecimal("4000"));
        BigDecimal override = new BigDecimal("5500");
        assertThat(EffectivePriceService.resolve(ut, MONDAY,  override))
                .isEqualByComparingTo("5500");
        assertThat(EffectivePriceService.resolve(ut, SATURDAY, override))
                .isEqualByComparingTo("5500");
    }

    @Test
    @DisplayName("в выходной используется weekend_price, если задан")
    void weekendPriceUsedOnSaturdaySunday() {
        UnitType ut = unitType(new BigDecimal("3000"), new BigDecimal("4000"));
        assertThat(EffectivePriceService.resolve(ut, SATURDAY, null))
                .isEqualByComparingTo("4000");
        assertThat(EffectivePriceService.resolve(ut, SUNDAY, null))
                .isEqualByComparingTo("4000");
    }

    @Test
    @DisplayName("в будний день — base_price")
    void basePriceUsedOnWeekday() {
        UnitType ut = unitType(new BigDecimal("3000"), new BigDecimal("4000"));
        assertThat(EffectivePriceService.resolve(ut, MONDAY, null))
                .isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("если weekend_price не задан — в выходной fallback на base")
    void fallbackToBaseWhenNoWeekendPrice() {
        UnitType ut = unitType(new BigDecimal("3000"), null);
        assertThat(EffectivePriceService.resolve(ut, SATURDAY, null))
                .isEqualByComparingTo("3000");
        assertThat(EffectivePriceService.resolve(ut, SUNDAY, null))
                .isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("если ни base, ни weekend не заданы — null (не исключение)")
    void allNullsGivesNull() {
        UnitType ut = unitType(null, null);
        assertThat(EffectivePriceService.resolve(ut, MONDAY, null)).isNull();
        assertThat(EffectivePriceService.resolve(ut, SATURDAY, null)).isNull();
    }

    @Test
    @DisplayName("isWeekend корректно опознаёт субботу и воскресенье")
    void weekendDetection() {
        assertThat(EffectivePriceService.isWeekend(MONDAY)).isFalse();
        assertThat(EffectivePriceService.isWeekend(LocalDate.of(2026, 10, 9))).isFalse();  // пятница
        assertThat(EffectivePriceService.isWeekend(SATURDAY)).isTrue();
        assertThat(EffectivePriceService.isWeekend(SUNDAY)).isTrue();
    }

    @Test
    @DisplayName("override null → fallback (не крашится на null-ovrd)")
    void nullOverrideFallsThrough() {
        UnitType ut = unitType(new BigDecimal("3300"), new BigDecimal("4200"));
        assertThat(EffectivePriceService.resolve(ut, SATURDAY, null))
                .isEqualByComparingTo("4200");
    }

    @Test
    @DisplayName("null unit_type безопасен (не NPE), возвращает null")
    void nullUnitTypeIsSafe() {
        assertThat(EffectivePriceService.fallbackPrice(null, MONDAY)).isNull();
    }
}
