package ru.rentoptima.service;

import ru.rentoptima.entity.PromoCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Расчёт стоимости проживания для виджета бронирования. Без БД — только арифметика.
 * <p>
 * Порядок: стоимость ночей → скидка за длительность → промокод → сбор за уборку.
 * <ul>
 *   <li>скидка за длительность: от 7 ночей — «недельная», от 28 — «месячная»; они
 *       не складываются, из подходящих берётся бо́льшая;</li>
 *   <li>промокод считается от стоимости ночей уже после скидки за длительность и не
 *       может увести её в минус;</li>
 *   <li>сбор за уборку скидками не уменьшается;</li>
 *   <li>каждая строка округляется до рубля, итог — сумма округлённых строк, чтобы
 *       разбивка у гостя сходилась с итогом.</li>
 * </ul>
 */
public final class WidgetPricing {

    public static final int WEEKLY_NIGHTS = 7;
    public static final int MONTHLY_NIGHTS = 28;

    private WidgetPricing() {}

    /**
     * @param nights      цена каждой ночи; price == null — цена на эту ночь не задана
     * @param promo       уже проверенный промокод или null
     * @return расчёт; {@link Quote#total()} == null, если цена задана не на все ночи
     */
    public static Quote quote(List<NightPrice> nights, BigDecimal cleaningFee,
                              int weeklyPercent, int monthlyPercent, int prepaymentPercent,
                              PromoCode promo) {
        int count = nights.size();
        BigDecimal accommodation = BigDecimal.ZERO;
        for (NightPrice n : nights) {
            if (n.price() == null) return Quote.incomplete(count, nights);
            accommodation = accommodation.add(rub(n.price()));
        }

        int lengthPercent = 0;
        if (count >= WEEKLY_NIGHTS) lengthPercent = Math.max(lengthPercent, weeklyPercent);
        if (count >= MONTHLY_NIGHTS) lengthPercent = Math.max(lengthPercent, monthlyPercent);
        lengthPercent = Math.max(0, Math.min(100, lengthPercent));
        BigDecimal lengthDiscount = percentOf(accommodation, lengthPercent);

        BigDecimal afterLength = accommodation.subtract(lengthDiscount);
        BigDecimal promoDiscount = BigDecimal.ZERO;
        if (promo != null && promo.getDiscountValue() != null) {
            promoDiscount = PromoCode.TYPE_PERCENT.equals(promo.getDiscountType())
                    ? percentOf(afterLength, promo.getDiscountValue().intValue())
                    : rub(promo.getDiscountValue());
            promoDiscount = promoDiscount.max(BigDecimal.ZERO).min(afterLength);
        }

        BigDecimal cleaning = cleaningFee == null ? BigDecimal.ZERO : rub(cleaningFee).max(BigDecimal.ZERO);
        BigDecimal total = afterLength.subtract(promoDiscount).add(cleaning);
        BigDecimal prepayment = percentOf(total, prepaymentPercent);

        return new Quote(count, nights, accommodation, lengthPercent, lengthDiscount,
                promo == null ? null : promo.getCode(), promoDiscount, cleaning, total,
                Math.max(0, Math.min(100, prepaymentPercent)), prepayment);
    }

    private static BigDecimal percentOf(BigDecimal amount, int percent) {
        int p = Math.max(0, Math.min(100, percent));
        if (p == 0) return BigDecimal.ZERO;
        return amount.multiply(BigDecimal.valueOf(p)).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    private static BigDecimal rub(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP);
    }

    public record NightPrice(LocalDate date, BigDecimal price) {}

    /**
     * @param accommodation  стоимость ночей до скидок
     * @param lengthPercent  применённая скидка за длительность, %; 0 — не применялась
     * @param promoCode      применённый промокод или null
     * @param total          итог к оплате; null — цена задана не на все ночи
     * @param prepayment     предоплата из итога; 0 — хозяин её не просит
     */
    public record Quote(int nights, List<NightPrice> breakdown, BigDecimal accommodation,
                        int lengthPercent, BigDecimal lengthDiscount,
                        String promoCode, BigDecimal promoDiscount,
                        BigDecimal cleaningFee, BigDecimal total,
                        int prepaymentPercent, BigDecimal prepayment) {

        static Quote incomplete(int nights, List<NightPrice> breakdown) {
            return new Quote(nights, breakdown, null, 0, null, null, null, null, null, 0, null);
        }

        public boolean complete() {
            return total != null;
        }

        /** Сумма всех скидок; null, если расчёт неполный. */
        public BigDecimal discountTotal() {
            return complete() ? lengthDiscount.add(promoDiscount) : null;
        }
    }
}
