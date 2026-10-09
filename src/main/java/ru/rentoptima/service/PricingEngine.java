package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PricingDecisionRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.service.CompetitorService.CompetitorAnalysis;
import ru.rentoptima.util.PdAnonymizer;
import ru.rentoptima.service.OverrideResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


// For peace in the World!

@Slf4j
@Service
@RequiredArgsConstructor
public class PricingEngine {

    private final PropertyRepository propertyRepo;
    private final BookingRepository bookingRepo;
    private final SettingsService settings;
    private final ProductionCalendarService prodCalendar;
    private final BookingStatsService statsService;
    private final AiPricingAdvisor aiAdvisor;
    private final CompetitorService competitorService;
    private final FeedbackAnalyticsService feedbackAnalytics;
    private final PricingLearningService learningService;
    private final PricingDecisionRepository decisionRepo;
    private final OverrideResolver overrideResolver;

    /**
     * Автопилот запускается каждый час.
     */
    public void runAutopilot() {

        List<Property> properties = propertyRepo.findAll().stream()
                .filter(Property::getActive)
                .toList();

        for (Property property : properties) {
            try {
                Long tenantId = property.getTenant().getId();

                String mode = settings.getValue(
                        tenantId,
                        "autopilot_mode"
                );

                if (mode == null || "OFF".equals(mode)) {
                    continue;
                }

                runForProperty(property, mode);

            } catch (Exception e) {
                log.error(
                        "Autopilot error for property {}: {}",
                        property.getName(),
                        e.getMessage(),
                        e
                );
            }
        }
    }

    /**
     * Расчёт рекомендаций для страницы админки.
     */
    public List<PricingRecommendation> getRecommendations(
            Long tenantId,
            Long propertyId
    ) {
        Property property = propertyRepo
                .findById(propertyId)
                .orElseThrow();

        return calculateRecommendations(
                property,
                LocalDate.now(),
                LocalDate.now().plusDays(60)
        );
    }

    /**
     * Основной цикл автопилота для одного объекта.
     */
    public void runForProperty(
            Property property,
            String mode
    ) {

        Long tenantId = property.getTenant().getId();

        int openAheadDays = settings.getIntValue(
                tenantId,
                "open_ahead_days",
                30
        );

        int autoDelta = settings.getIntValue(
                tenantId,
                "auto_price_delta",
                50
        );

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(openAheadDays);

        /*
         * 2. Рассчитываем рекомендации алгоритма
         */
        List<PricingRecommendation> recs =
                calculateRecommendations(property, from, to);

        /*
         * 3. Получаем конкурентный анализ
         */
        CompetitorAnalysis competitorAnalysis = null;
        try {
            boolean competitorEnabled = "true".equalsIgnoreCase(
                    settings.getValue(tenantId, "competitor_search_enabled"));
            if (competitorEnabled) {
                competitorAnalysis = competitorService.analyzeCompetitorPrices(
                        tenantId, from, to);
                if (competitorAnalysis.competitorCount() > 0) {
                    log.info("Autopilot [{}]: competitor data available — {} competitors, "
                                    + "{} date points for {}",
                            mode,
                            competitorAnalysis.competitorCount(),
                            competitorAnalysis.avgPriceByDate().size(),
                            property.getName());
                }
            }
        } catch (Exception e) {
            log.warn("Competitor analysis failed for {}: {}",
                    property.getName(), e.getMessage());
        }

        /*
         * 4. AI adjustments (with competitor data)
         */
        Map<LocalDate, Double> aiAdjustments = Map.of();
        try {
            aiAdjustments = aiAdvisor.getAdjustments(
                    property, recs, competitorAnalysis);
        } catch (Exception e) {
            log.warn("AI pricing skipped: {}", e.getMessage());
        }
        final Map<LocalDate, Double> adjustments = aiAdjustments;

        double bookingPaceVal = 0;
        try {
            bookingPaceVal = statsService.getBookingPace(tenantId).currentOccupancy();
        } catch (Exception e) {
            log.debug("BookingPace fetch failed: {}", e.getMessage());
        }
        final double bookingPaceFinal = bookingPaceVal;

        /*
         * 5. Рейтинговый множитель (единожды на весь цикл)
         */
        double ratingMult = 1.0;
        try {
            ratingMult = feedbackAnalytics.priceMultiplier(tenantId, property.getId());
            if (ratingMult != 1.0) {
                log.info("Autopilot [{}]: rating multiplier {} for {}",
                        mode, ratingMult, property.getName());
            }
        } catch (Exception e) {
            log.warn("Rating multiplier calculation failed: {}", e.getMessage());
        }

        int logged = 0;

        /*
         * 6. Фильтруем даты и применяем корректировки
         */
        int skipped = 0; // that`s ok

        for (PricingRecommendation rec : recs) {

            if (rec.status() == DayStatus.BOOKED) {
                log.debug("Autopilot: skip BOOKED date {} for {}",
                        rec.date(), property.getName());
                continue;
            }

            /*
             * Apply AI + competitor gravity + rating multiplier
             */
            BigDecimal finalPrice = applyAiAdjustment(
                    rec, adjustments, tenantId);

            if (!adjustments.containsKey(rec.date())
                    && competitorAnalysis != null
                    && competitorAnalysis.avgPriceByDate().containsKey(rec.date())) {

                finalPrice = applyCompetitorGravity(
                        finalPrice,
                        competitorAnalysis.avgPriceByDate().get(rec.date()),
                        tenantId);
            }

            // Rating-based multiplier
            if (ratingMult != 1.0) {
                finalPrice = finalPrice
                        .multiply(BigDecimal.valueOf(ratingMult))
                        .setScale(0, RoundingMode.HALF_UP);
            }

            // Manual override multiplier (from AI chat commands)
            double overrideMult = overrideResolver.getActivePriceMultiplier(
                    tenantId, property.getId(), rec.date());
            if (overrideMult != 1.0) {
                finalPrice = finalPrice
                        .multiply(BigDecimal.valueOf(overrideMult))
                        .setScale(0, RoundingMode.HALF_UP);
                log.debug("Manual override multiplier {} applied for {}", overrideMult, rec.date());
            }

            int priceInt = finalPrice.intValue();
            int minStayInt = rec.recommendedMinStay();

            // min_stay override
            Integer overrideStay = overrideResolver.getActiveMinStay(
                    tenantId, property.getId(), rec.date());
            if (overrideStay != null) {
                minStayInt = overrideStay;
                log.debug("Override min_stay {} applied for {}", overrideStay, rec.date());
            }

            // Check if state actually changed vs last known
            var latest = decisionRepo.findLatest(tenantId, property.getId(), rec.date());
            boolean changed = latest.isEmpty()
                    || !latest.get().getPrice().equals(priceInt)
                    || !latest.get().getMinStay().equals(minStayInt);

            if (changed) {
                logged++;
            } else {
                skipped++;
            }

            // Log decision (only if state changed — internally checks itself)
            try {
                learningService.logDecisionIfChanged(
                        tenantId, property.getId(), rec.date(),
                        priceInt, minStayInt,
                        adjustments.get(rec.date()),
                        (int) java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), rec.date()),
                        rec.windowLen(),
                        rec.date().getDayOfWeek().getValue() >= 5,
                        prodCalendar.isHoliday(rec.date()),
                        bookingPaceFinal,
                        competitorAnalysis != null && competitorAnalysis.avgPriceByDate().containsKey(rec.date())
                                ? competitorAnalysis.avgPriceByDate().get(rec.date()).intValue()
                                : null
                );
            } catch (Exception e) {
                log.warn("Log decision failed: {}", e.getMessage());
            }
        }

        log.info("Autopilot: {} recommendations updated for {} ({} unchanged)",
                logged, property.getName(), skipped);
    }

    /**
     * Мягкое притяжение к рыночной цене конкурентов.
     */
    private BigDecimal applyCompetitorGravity(
            BigDecimal ourPrice,
            BigDecimal competitorAvg,
            Long tenantId
    ) {
        double ours = ourPrice.doubleValue();
        double market = competitorAvg.doubleValue();

        if (market <= 0) return ourPrice;

        double deviation = (ours - market) / market;

        if (Math.abs(deviation) <= 0.15) return ourPrice;

        double adjusted = ours + (market - ours) * 0.30;

        int floor = settings.getIntValue(tenantId, "min_price_floor", 2500);
        int ceil = settings.getIntValue(tenantId, "max_price_ceiling", 10000);
        int result = (int) Math.round(adjusted);
        result = Math.max(floor, Math.min(ceil, result));

        log.info("Competitor gravity: {}₽ → {}₽ (market avg {}₽, deviation {}%)",
                ourPrice, result, competitorAvg, Math.round(deviation * 1000) / 10.0);

        return BigDecimal.valueOf(result);
    }

    /**
     * Основной расчёт рекомендаций.
     */
    public List<PricingRecommendation> calculateRecommendations(
            Property property,
            LocalDate from,
            LocalDate to
    ) {

        Long tenantId = property.getTenant().getId();

        Map<String, String> s = settings.getSettingsMap(tenantId);

        int weekdayBase = parseInt(s, "weekday_base_price", 3200);
        int weekendBase = parseInt(s, "weekend_base_price", 4200);
        int floorPrice = parseInt(s, "min_price_floor", 2500);
        int ceilPrice = parseInt(s, "max_price_ceiling", 6000);
        int maxMinStay = parseInt(s, "max_min_stay", 10);
        int cleaningCost = parseInt(s, "cleaning_cost", 1400);

        double markupPct = Double.parseDouble(
                s.getOrDefault("platform_markup_pct", "18")
        ) / 100.0;

        List<Booking> bookings = bookingRepo.findActiveInRange(
                tenantId, property.getId(), from, to);

        Set<LocalDate> bookedDates = new HashSet<>();
        for (Booking b : bookings) {
            for (LocalDate d = b.getCheckIn();
                 d.isBefore(b.getCheckOut());
                 d = d.plusDays(1)) {
                if (!d.isBefore(from) && !d.isAfter(to))
                    bookedDates.add(d);
            }
        }

        List<int[]> windows = new ArrayList<>();
        LocalDate winStart = null;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!bookedDates.contains(d)) {
                if (winStart == null) winStart = d;
            } else if (winStart != null) {
                windows.add(new int[]{
                        (int) winStart.toEpochDay(),
                        (int) d.minusDays(1).toEpochDay()});
                winStart = null;
            }
        }
        if (winStart != null) {
            windows.add(new int[]{
                    (int) winStart.toEpochDay(),
                    (int) to.toEpochDay()});
        }

        List<BookingStatsService.GapInfo> gaps =
                statsService.detectGaps(tenantId, from, to);

        Set<LocalDate> gapDates = new HashSet<>();
        for (var gap : gaps) {
            for (LocalDate d = gap.from();
                 !d.isAfter(gap.to().minusDays(1));
                 d = d.plusDays(1)) {
                gapDates.add(d);
            }
        }

        List<PricingRecommendation> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {

            final LocalDate d = date;
            long daysAhead = ChronoUnit.DAYS.between(today, d);

            if (bookedDates.contains(d)) {
                result.add(new PricingRecommendation(
                        d, BigDecimal.ZERO, BigDecimal.ZERO,
                        0, 0, BigDecimal.ZERO,
                        DayStatus.BOOKED, "Забронировано", 100, 0));
                continue;
            }

            int dayEpoch = (int) d.toEpochDay();
            int windowLen = 1;
            for (int[] w : windows) {
                if (dayEpoch >= w[0] && dayEpoch <= w[1]) {
                    windowLen = w[1] - w[0] + 1;
                    break;
                }
            }

            boolean isWeekend = d.getDayOfWeek().getValue() >= 5;
            boolean isHoliday = prodCalendar.isHoliday(d);
            boolean isGap = gapDates.contains(d) || windowLen <= 2;

            int basePrice = (isWeekend || isHoliday) ? weekendBase : weekdayBase;

            double multiplier;
            int minStay;
            String reason;
            int confidence;

            if (daysAhead > 45) {
                multiplier = 1.03;
                confidence = 55;
            } else if (daysAhead > 21) {
                multiplier = 1.0;
                confidence = 65;
            } else if (daysAhead > 14) {
                multiplier = 0.98;
                confidence = 72;
            } else if (daysAhead > 7) {
                multiplier = 0.95;
                confidence = 77;
            } else if (daysAhead > 3) {
                multiplier = 0.90;
                confidence = 83;
            } else {
                multiplier = 0.85;
                confidence = 90;
            }

            double rawMinStay = 1.0 + 0.055 * daysAhead;

            double priceRelief = 0;
            if (multiplier > 1.0 || isHoliday) {
                double effectiveBoost = Math.max(multiplier - 1.0, 0)
                        + (isHoliday ? 0.10 : 0);
                priceRelief = effectiveBoost * 6.0;
            }

            minStay = (int) Math.round(rawMinStay - priceRelief);
            minStay = Math.max(1, Math.min(minStay,
                    Math.min(windowLen, maxMinStay)));

            reason = daysAhead > 30
                    ? "Далеко (" + daysAhead + "д) — окно "
                    + windowLen + "н, срок " + minStay + "н"
                    : daysAhead > 7
                    ? "Средний горизонт — срок " + minStay + "н"
                    : "Ближний горизонт — срок " + minStay + "н";

            if (isGap) {
                multiplier *= 0.88;
                minStay = 1;
                reason = "Gap (окно " + windowLen + "н) — скидка";
                confidence = 88;
            }

            if (isHoliday) {
                double holidayMult = prodCalendar.getHolidayMultiplier(d);
                multiplier *= holidayMult;
                reason += holidayMult >= 1.5 ? " + ПИК праздник"
                        : holidayMult >= 1.25 ? " + большой праздник"
                        : " + праздник";
            }

            if (windowLen >= 7 && daysAhead > 14 && !isGap) {
                multiplier *= 1.05;
                reason += " (длинное окно)";
            }

            minStay = Math.max(1, Math.min(minStay, windowLen));

            int price = (int) Math.round(basePrice * multiplier);
            price = Math.max(floorPrice, Math.min(ceilPrice, price));

            BigDecimal netPerNight = BigDecimal.valueOf(price)
                    .subtract(BigDecimal.valueOf(cleaningCost));

            int delta = price - basePrice;

            result.add(new PricingRecommendation(
                    d,
                    BigDecimal.valueOf(basePrice),
                    BigDecimal.valueOf(price),
                    delta,
                    minStay,
                    netPerNight,
                    isGap ? DayStatus.GAP : DayStatus.FREE,
                    reason,
                    confidence,
                    windowLen
            ));
        }

        return result;
    }

    private int parseInt(Map<String, String> map, String key, int def) {
        try {
            return Integer.parseInt(
                    map.getOrDefault(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public enum DayStatus {
        FREE, BOOKED, GAP
    }

    private BigDecimal applyAiAdjustment(
            PricingRecommendation rec,
            Map<LocalDate, Double> adjustments,
            Long tenantId
    ) {
        if (!adjustments.containsKey(rec.date())) return rec.recommendedPrice();
        double multiplier = adjustments.get(rec.date());
        int floor = settings.getIntValue(tenantId, "min_price_floor", 2500);
        int ceil = settings.getIntValue(tenantId, "max_price_ceiling", 10000);
        int adjusted = (int) Math.round(
                rec.recommendedPrice().doubleValue() * multiplier);
        adjusted = Math.max(floor, Math.min(ceil, adjusted));
        log.debug("AI adjusted {} from {} to {} (×{})",
                rec.date(), rec.recommendedPrice(), adjusted, multiplier);
        return BigDecimal.valueOf(adjusted);
    }

    public record PricingRecommendation(
            LocalDate date,
            BigDecimal currentPrice,
            BigDecimal recommendedPrice,
            int priceDelta,
            int recommendedMinStay,
            BigDecimal netPerNight,
            DayStatus status,
            String reason,
            int confidence,
            int windowLen
    ) {}
}
