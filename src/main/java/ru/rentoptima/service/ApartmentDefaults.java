package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * «Значения по умолчанию для новых квартир» из настроек: город и цены в будни и выходные.
 * Они только подставляются подсказкой (placeholder) в поля мастера и формы объекта —
 * ни в расчётах, ни в автопилоте не участвуют: цена — всегда цена квартиры
 * ({@link EffectivePriceService}). Пустое значение — подсказки нет.
 */
@Service
@RequiredArgsConstructor
public class ApartmentDefaults {

    public static final String CITY = "city";
    public static final String WEEKDAY = "weekday_base_price";
    public static final String WEEKEND = "weekend_base_price";

    private final SettingsService settings;

    public record Defaults(String city, String weekdayPrice, String weekendPrice) {}

    public Defaults forTenant(Long tenantId) {
        Map<String, String> s = settings.getSettingsMap(tenantId);
        return new Defaults(blankToNull(s.get(CITY)), price(s.get(WEEKDAY)), price(s.get(WEEKEND)));
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** Только положительное целое — иначе подсказки нет. */
    private static String price(String v) {
        String t = blankToNull(v);
        if (t == null) return null;
        try {
            return Integer.parseInt(t) > 0 ? t : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
