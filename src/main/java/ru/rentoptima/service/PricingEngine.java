package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.service.CompetitorService.CompetitorAnalysis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PricingEngine {

    private final PropertyRepository propertyRepo;
    private final BookingRepository bookingRepo;
    private final SettingsService settings;
    private final ProductionCalendarService prodCalendar;
    private final RealtyCalendarClient rcClient;
    private final BookingStatsService statsService;
    private final AiPricingAdvisor aiAdvisor;
    private final RcSyncService rcSyncService;
    private final CompetitorService competitorService;

    /**
     * Автопилот запускается каждый час.
     */
    public void runAutopilot() {

        List<Property> properties = propertyRepo.findAll().stream()
                .filter(p -> p.getActive()
                        && p.getRcObjectId() != null
                        && !p.getRcObjectId().isBlank())
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
         * 1. Получаем состояние календаря RC
         */
        CalendarState calendarState;
        try {
            calendarState = loadCalendarState(
                    property.getRcObjectId(),
                    from,
                    to
            );
        } catch (Exception e) {
            log.error(
                    "Autopilot [{}]: cannot read RC calendar for {}. "
                            + "NO prices will be changed: {}",
                    mode,
                    property.getName(),
                    e.getMessage(),
                    e
            );
            return;
        }

        /*
         * 2. Рассчитываем рекомендации алгоритма
         */
        List<PricingRecommendation> recs =
                calculateRecommendations(property, from, to);

        // Sync bookings from RC event_calendars
        try {
            syncRcBookings(property, from, to);
        } catch (Exception e) {
            log.warn("RC bookings sync failed for {}: {}",
                    property.getName(), e.getMessage());
        }

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

        List<RealtyCalendarClient.SpecialPrice> items =
                new ArrayList<>();

        /*
         * 5. Фильтруем даты и применяем корректировки
         */
        for (PricingRecommendation rec : recs) {

            if (rec.status() == DayStatus.BOOKED) {
                log.debug("Autopilot: skip BOOKED date {} for {}",
                        rec.date(), property.getName());
                continue;
            }

            if (calendarState.closedDates().contains(rec.date())) {
                log.info("Autopilot: skip CLOSED date {} for {}",
                        rec.date(), property.getName());
                continue;
            }

            if ("SOFT".equals(mode)
                    && Math.abs(rec.priceDelta()) > autoDelta) {
                log.debug("SOFT: skip large change for {} (delta={}₽)",
                        rec.date(), rec.priceDelta());
                continue;
            }

            /*
             * Apply competitor-aware price: if we have competitor data
             * and no AI adjustment for this date, apply a soft competitor
             * gravity factor to avoid being too far from the market.
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

            items.add(
                    new RealtyCalendarClient.SpecialPrice(
                            rec.date(),
                            finalPrice,
                            rec.recommendedMinStay()
                    )
            );
        }

        /*
         * 6. Отправляем цены
         */
        if (items.isEmpty()) {
            log.info("Autopilot [{}]: no items to push for {}",
                    mode, property.getName());
            return;
        }

        log.info("Autopilot [{}]: pushing {} price updates for {}",
                mode, items.size(), property.getName());

        rcClient.saveSpecialPrices(
                property.getRcObjectId(),
                items
        );
    }

    /**
     * Мягкое притяжение к рыночной цене конкурентов.
     * Если наша цена отличается от средней конкурентов более чем на 15%,
     * подтягиваем на 30% разницы в сторону рынка.
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

        // Only adjust if we're more than 15% off market
        if (Math.abs(deviation) <= 0.15) return ourPrice;

        // Pull 30% toward market price
        double adjusted = ours + (market - ours) * 0.30;

        int floor = settings.getIntValue(tenantId, "min_price_floor", 2500);
        int ceil = settings.getIntValue(tenantId, "max_price_ceiling", 10000);
        int result = (int) Math.round(adjusted);
        result = Math.max(floor, Math.min(ceil, result));

        log.info("Competitor gravity: {}₽ → {}₽ (market avg {}₽, deviation {:.1f}%)",
                ourPrice, result, competitorAvg, deviation * 100);

        return BigDecimal.valueOf(result);
    }

    /**
     * Получает календарь RC и извлекает закрытые даты.
     */
    private CalendarState loadCalendarState(
            String rcObjectId,
            LocalDate from,
            LocalDate to
    ) {

        JsonNode response = rcClient.getSpecialPrices(
                rcObjectId, from, to);

        if (response == null || response.isMissingNode()) {
            throw new IllegalStateException(
                    "RealtyCalendar returned empty calendar response");
        }

        JsonNode items = response.path("items");

        if (!items.isArray()) {
            throw new IllegalStateException(
                    "RealtyCalendar calendar response does not contain "
                            + "an 'items' array");
        }

        Set<LocalDate> closedDates = new HashSet<>();

        for (JsonNode item : items) {

            String dateText = item.path("date").asText(null);

            if (dateText == null || dateText.isBlank()) {
                log.warn("RC calendar item without date: {}", item);
                continue;
            }

            LocalDate date;
            try {
                date = LocalDate.parse(dateText);
            } catch (Exception e) {
                log.warn("Cannot parse RC calendar date '{}'", dateText);
                continue;
            }

            boolean closed = item
                    .path("closed").path("actual").path("value")
                    .asBoolean(false);

            boolean closedOnArrival = item
                    .path("closed_on_arrivial").path("actual").path("value")
                    .asBoolean(false);

            boolean closedOnDeparture = item
                    .path("closed_on_departure").path("actual").path("value")
                    .asBoolean(false);

            if (closed || closedOnArrival || closedOnDeparture) {
                closedDates.add(date);
                log.debug("RC calendar: date {} is restricted "
                                + "(closed={}, arrival={}, departure={})",
                        date, closed, closedOnArrival, closedOnDeparture);
            }
        }

        log.info("RC calendar loaded: {} closed/restricted dates between {} and {}",
                closedDates.size(), from, to);

        return new CalendarState(closedDates);
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
                        DayStatus.BOOKED, "Забронировано", 100));
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
                multiplier *= 1.12;
                reason += " + праздник";
            }

            if (windowLen >= 7 && daysAhead > 14 && !isGap) {
                multiplier *= 1.05;
                reason += " (длинное окно)";
            }

            minStay = Math.max(1, Math.min(minStay, windowLen));

            int rcPrice = (int) Math.round(basePrice * multiplier);
            rcPrice = Math.max(floorPrice, Math.min(ceilPrice, rcPrice));

            BigDecimal netPerNight = BigDecimal.valueOf(rcPrice)
                    .subtract(BigDecimal.valueOf(cleaningCost));

            int delta = rcPrice - basePrice;

            result.add(new PricingRecommendation(
                    d,
                    BigDecimal.valueOf(basePrice),
                    BigDecimal.valueOf(rcPrice),
                    delta,
                    minStay,
                    netPerNight,
                    isGap ? DayStatus.GAP : DayStatus.FREE,
                    reason,
                    confidence
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

    /**
     * Публичный триггер синхронизации из RC для одного объекта.
     * Возвращает: prices=map дата→цена, minStays=map дата→мин.срок
     */
    public RcSyncResult triggerRcSyncWithPrices(
            Property property, LocalDate from, LocalDate to) {

        Map<LocalDate, Integer> rcPrices = new HashMap<>();
        Map<LocalDate, Integer> rcMinStays = new HashMap<>();
        try {
            JsonNode response = rcClient.getEventCalendars(
                    property.getRcObjectId(), from, to);
            if (response == null || !response.has("items")
                    || !response.get("items").isArray()
                    || response.get("items").isEmpty()) {
                return new RcSyncResult(rcPrices, rcMinStays);
            }

            JsonNode apt = response.get("items").get(0);
            syncRcBookingsFromNode(property, apt);

            JsonNode specialPrices = apt.path("special_prices");
            if (specialPrices.isArray()) {
                for (JsonNode sp : specialPrices) {
                    if (sp.path("is_delete").asBoolean(false)) continue;

                    String beginStr = sp.path("begin_date").asText(null);
                    String endStr = sp.path("end_date").asText(null);
                    if (beginStr == null || endStr == null) continue;

                    double price = sp.path("price").asDouble(0);
                    int minStay = sp.path("min_stay_through").asInt(0);

                    try {
                        LocalDate start = LocalDate.parse(beginStr);
                        LocalDate end = LocalDate.parse(endStr);
                        for (LocalDate d = start; d.isBefore(end);
                             d = d.plusDays(1)) {
                            if (price > 0) rcPrices.put(d, (int) Math.round(price));
                            if (minStay > 0) rcMinStays.put(d, minStay);
                        }
                    } catch (Exception e) {
                        log.warn("Cannot parse special_price date range: {}",
                                e.getMessage());
                    }
                }
            }
            log.info("RC state for {}: {} prices, {} min_stays",
                    property.getName(), rcPrices.size(), rcMinStays.size());
        } catch (Exception e) {
            log.warn("triggerRcSyncWithPrices failed for {}: {}",
                    property.getName(), e.getMessage());
        }
        return new RcSyncResult(rcPrices, rcMinStays);
    }

    public record RcSyncResult(
            Map<LocalDate, Integer> prices,
            Map<LocalDate, Integer> minStays
    ) {}

    public void triggerRcSync(Property property, LocalDate from, LocalDate to) {
        try {
            syncRcBookings(property, from, to);
        } catch (Exception e) {
            log.warn("Manual RC sync failed for {}: {}",
                    property.getName(), e.getMessage());
        }
    }

    private void syncRcBookingsFromNode(Property property, JsonNode apt) {
        JsonNode events = apt.path("events");
        if (!events.isArray()) return;

        log.info("RC event_calendars: {} raw events for {}",
                events.size(), property.getName());

        List<RcBooking> rcBookings = new ArrayList<>();
        int skippedDeleted = 0;

        for (JsonNode ev : events) {
            if (ev.path("is_delete").asBoolean(false)) {
                skippedDeleted++;
                continue;
            }
            if (!"booked".equals(ev.path("status").asText(""))) continue;

            String beginStr = ev.path("begin_date").asText(null);
            String endStr = ev.path("end_date").asText(null);
            if (beginStr == null || endStr == null) continue;

            try {
                LocalDate start = LocalDate.parse(beginStr);
                LocalDate end = LocalDate.parse(endStr);
                long rcId = ev.path("id").asLong(0);
                String guest = ev.path("client").path("fio").asText(null);
                String phone = ev.path("client").path("phone").asText(null);
                double amount = ev.path("amount").asDouble(0);
                int sourceId = ev.path("source_id").asInt(0);

                if ((guest == null || guest.isBlank()) && amount == 0.0) {
                    guest = "Ручное закрытие RC";
                }

                rcBookings.add(new RcBooking(
                        rcId, start, end, guest, phone, amount, sourceId));
            } catch (Exception e) {
                log.warn("Cannot parse RC event: {}", e.getMessage());
            }
        }

        log.info("RC sync {}: {} bookings (skipped {} deleted)",
                property.getName(), rcBookings.size(), skippedDeleted);

        if (!rcBookings.isEmpty()) {
            rcSyncService.syncBookings(property, rcBookings);
        }
    }

    private void syncRcBookings(Property property, LocalDate from, LocalDate to) {
        JsonNode response = rcClient.getEventCalendars(
                property.getRcObjectId(), from, to);
        if (response == null || !response.has("items")
                || !response.get("items").isArray()
                || response.get("items").isEmpty()) {
            log.info("RC event_calendars empty for {} in {}-{}",
                    property.getName(), from, to);
            return;
        }

        JsonNode apt = response.get("items").get(0);
        JsonNode events = apt.path("events");
        if (!events.isArray()) return;

        log.info("RC event_calendars returned {} raw events for {}",
                events.size(), property.getName());

        List<RcBooking> rcBookings = new ArrayList<>();
        int skippedDeleted = 0;

        for (JsonNode ev : events) {
            if (ev.path("is_delete").asBoolean(false)) {
                skippedDeleted++;
                continue;
            }
            if (!"booked".equals(ev.path("status").asText(""))) continue;

            String beginStr = ev.path("begin_date").asText(null);
            String endStr = ev.path("end_date").asText(null);
            if (beginStr == null || endStr == null) continue;

            try {
                LocalDate start = LocalDate.parse(beginStr);
                LocalDate end = LocalDate.parse(endStr);
                long rcId = ev.path("id").asLong(0);
                String guest = ev.path("client").path("fio").asText(null);
                String phone = ev.path("client").path("phone").asText(null);
                double amount = ev.path("amount").asDouble(0);
                int sourceId = ev.path("source_id").asInt(0);

                if ((guest == null || guest.isBlank()) && amount == 0.0) {
                    guest = "Ручное закрытие RC";
                }

                rcBookings.add(new RcBooking(
                        rcId, start, end, guest, phone, amount, sourceId));
            } catch (Exception e) {
                log.warn("Cannot parse RC event: {}", e.getMessage());
            }
        }

        log.info("RC sync {}: {} bookings (skipped {} deleted)",
                property.getName(), rcBookings.size(), skippedDeleted);

        if (!rcBookings.isEmpty()) {
            rcSyncService.syncBookings(property, rcBookings);
        }
    }

    public record RcBooking(
            long rcId, LocalDate checkIn, LocalDate checkOut,
            String guestName, String phone, double amount, int sourceId
    ) {}

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

    private record CalendarState(Set<LocalDate> closedDates) {}

    public record PricingRecommendation(
            LocalDate date,
            BigDecimal currentPrice,
            BigDecimal recommendedPrice,
            int priceDelta,
            int recommendedMinStay,
            BigDecimal netPerNight,
            DayStatus status,
            String reason,
            int confidence
    ) {}
}
