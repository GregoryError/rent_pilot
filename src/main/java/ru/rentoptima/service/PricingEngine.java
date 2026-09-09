package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PropertyRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pricing Engine — window-based pricing algorithm.
 *
 * Core insight: price and min_stay are set per FREE WINDOW (gap between bookings),
 * not per individual date. Goal: maximize ADR × stay_length first,
 * then buy occupancy with discounts as dates approach.
 */
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
    private final RcSyncService rcSyncService;
    private final AiPricingAdvisor aiAdvisor;

    // Called by AutopilotSchedulerService
    public void runForProperty(Property property, String mode) {
        Long tenantId = property.getTenant().getId();
        int openAheadDays = settings.getIntValue(tenantId, "open_ahead_days", 80);
        int autoDelta = settings.getIntValue(tenantId, "auto_price_delta", 50);

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(openAheadDays);

        // 1. Read RC calendar state (bookings + closed dates)
        CalendarState calendarState;
        try {
            calendarState = loadCalendarState(property.getRcObjectId(), from, to);
        } catch (Exception e) {
            log.error("Cannot read RC calendar for {}, skipping: {}", property.getName(), e.getMessage());
            return;
        }

        // 2. Sync RC bookings → our DB
        syncBookingsFromRc(property, calendarState.rcBookings());

        // 3. Calculate window-based recommendations
        List<PricingRecommendation> recs = calculateRecommendations(property, from, to);

        // 4. Build RC payload
        // AI adjustments (graceful fallback if unavailable)
        Map<LocalDate, Double> aiAdjustments = Map.of();
        try {
            aiAdjustments = aiAdvisor.getAdjustments(property, recs);
        } catch (Exception e) {
            log.warn("AI pricing skipped: {}", e.getMessage());
        }
        final Map<LocalDate, Double> adjustments = aiAdjustments;

        List<RealtyCalendarClient.SpecialPrice> items = new ArrayList<>();
        for (PricingRecommendation rec : recs) {
            if (rec.status() == DayStatus.BOOKED) continue;
            if (calendarState.closedDates().contains(rec.date())) {
                log.debug("Skip CLOSED date {} for {}", rec.date(), property.getName());
                continue;
            }
            if ("SOFT".equals(mode) && Math.abs(rec.priceDelta()) > autoDelta) {
                log.debug("SOFT: skip large change {} (delta={})", rec.date(), rec.priceDelta());
                continue;
            }
            // Apply AI multiplier if present
            BigDecimal finalPrice = rec.recommendedPrice();
            if (adjustments.containsKey(rec.date())) {
                double multiplier = adjustments.get(rec.date());
                int floorPrice = settings.getIntValue(tenantId, "min_price_floor", 2500);
                int ceilPrice  = settings.getIntValue(tenantId, "max_price_ceiling", 10000);
                int adjusted = (int) Math.round(finalPrice.doubleValue() * multiplier);
                adjusted = Math.max(floorPrice, Math.min(ceilPrice, adjusted));
                finalPrice = BigDecimal.valueOf(adjusted);
                log.debug("AI adjusted {} from {} to {} (×{})", rec.date(), rec.recommendedPrice(), finalPrice, multiplier);
            }

            items.add(new RealtyCalendarClient.SpecialPrice(
                    rec.date(),
                    finalPrice,
                    rec.recommendedMinStay()
            ));
        }

        if (items.isEmpty()) {
            log.info("Autopilot [{}]: nothing to push for {}", mode, property.getName());
            return;
        }

        log.info("Autopilot [{}]: pushing {} updates for {}", mode, items.size(), property.getName());
        rcClient.saveSpecialPrices(property.getRcObjectId(), items);
    }

    /** Sync bookings from RC GET response into our DB */
    private void syncBookingsFromRc(Property property, List<RcBooking> rcBookings) {
        if (rcBookings.isEmpty()) return;
        rcSyncService.syncBookings(property, rcBookings);
    }

    public List<PricingRecommendation> getRecommendations(Long tenantId, Long propertyId) {
        Property property = propertyRepo.findById(propertyId).orElseThrow();
        return calculateRecommendations(property, LocalDate.now(), LocalDate.now().plusDays(60));
    }

    /**
     * Window-based pricing algorithm.
     *
     * Algorithm:
     * 1. Find all free windows (contiguous free date ranges between bookings)
     * 2. For each window, set min_stay based on window length and days ahead
     * 3. Price based on base price × modifiers (weekend, holiday, urgency, window size)
     * 4. Goal: sell whole window at good price first, then fragment if needed
     */
    public List<PricingRecommendation> calculateRecommendations(
            Property property, LocalDate from, LocalDate to) {

        Long tenantId = property.getTenant().getId();
        Map<String, String> s = settings.getSettingsMap(tenantId);

        int weekdayBase  = parseInt(s, "weekday_base_price", 3200);
        int weekendBase  = parseInt(s, "weekend_base_price", 4200);
        int floorPrice   = parseInt(s, "min_price_floor", 2500);
        int ceilPrice    = parseInt(s, "max_price_ceiling", 10000);
        int maxMinStay   = parseInt(s, "max_min_stay", 10);
        int cleaningCost = parseInt(s, "cleaning_cost", 1400);
        int openAheadDays = parseInt(s, "open_ahead_days", 80);
        double markupPct = parseDouble(s, "platform_markup_pct", 18.0) / 100.0;

        List<Booking> bookings = bookingRepo.findActiveInRange(tenantId, property.getId(), from, to);
        Set<LocalDate> bookedDates = buildBookedDates(bookings, from, to);

        // Find all free windows
        List<FreeWindow> windows = findFreeWindows(from, to, bookedDates, openAheadDays);

        List<PricingRecommendation> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            final LocalDate d = date;

            if (bookedDates.contains(d)) {
                result.add(new PricingRecommendation(
                        d, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0,
                        BigDecimal.ZERO, DayStatus.BOOKED, "Забронировано", 100));
                continue;
            }

            // Find which window this date belongs to
            FreeWindow window = windows.stream()
                    .filter(w -> !d.isBefore(w.start()) && !d.isAfter(w.end()))
                    .findFirst().orElse(null);

            if (window == null) {
                // Beyond open_ahead_days — soft lock
                result.add(new PricingRecommendation(
                        d, BigDecimal.valueOf(weekdayBase), BigDecimal.valueOf(weekdayBase),
                        0, maxMinStay, BigDecimal.ZERO, DayStatus.FREE,
                        "За пределами горизонта открытия", 40));
                continue;
            }

            long daysAhead = ChronoUnit.DAYS.between(today, d);
            int windowLen = window.lengthDays();
            boolean isWeekend = d.getDayOfWeek().getValue() >= 5;
            boolean isHoliday = prodCalendar.isHoliday(d);
            boolean isGap = windowLen <= 2; // very short window = gap

            int basePrice = (isWeekend || isHoliday) ? weekendBase : weekdayBase;

            // --- Window-based min_stay ---
            // Start with wanting to sell the whole window
            // Gradually open shorter stays as dates approach
            int minStay;
            double priceMultiplier;
            String reason;
            int confidence;

            if (daysAhead > 60) {
                // Very far: sell full window or maxMinStay, premium price
                minStay = Math.min(windowLen, maxMinStay);
                priceMultiplier = 1.05;
                reason = "Далеко (" + daysAhead + "д) — полное окно (" + windowLen + "н)";
                confidence = 50;
            } else if (daysAhead > 45) {
                minStay = Math.min(windowLen, Math.max(7, maxMinStay - 1));
                priceMultiplier = 1.02;
                reason = "45-60 дней, окно=" + windowLen + "н";
                confidence = 55;
            } else if (daysAhead > 30) {
                minStay = Math.min(windowLen, Math.max(5, windowLen * 2 / 3));
                priceMultiplier = 1.0;
                reason = "30-45 дней";
                confidence = 62;
            } else if (daysAhead > 21) {
                minStay = Math.min(windowLen, Math.max(4, windowLen / 2));
                priceMultiplier = 1.0;
                reason = "21-30 дней";
                confidence = 67;
            } else if (daysAhead > 14) {
                minStay = Math.min(windowLen, Math.max(3, windowLen / 3));
                priceMultiplier = 0.98;
                reason = "14-21 день";
                confidence = 72;
            } else if (daysAhead > 7) {
                minStay = windowLen >= 4 ? 3 : (windowLen >= 2 ? 2 : 1);
                priceMultiplier = 0.95;
                reason = "7-14 дней — снижаем условия";
                confidence = 77;
            } else if (daysAhead > 3) {
                minStay = windowLen >= 2 ? 2 : 1;
                priceMultiplier = 0.90;
                reason = "3-7 дней — открываем 2 ночи";
                confidence = 83;
            } else {
                minStay = 1;
                priceMultiplier = 0.85;
                reason = "< 3 дня — последний шанс";
                confidence = 90;
            }

            // Gap: very short window — don't force min_stay=1 if it ruins adjacent bookings
            // Instead: 1-day gap → min_stay=1 (no choice), 2-day gap → min_stay=1 with discount
            if (isGap) {
                minStay = 1;
                priceMultiplier *= 0.88;
                reason = "Gap (" + windowLen + "д) — скидка";
                confidence = 88;
            }

            // Weekend/holiday premium
            if (isHoliday && !isGap) {
                priceMultiplier *= 1.12;
                reason += " + праздник";
            }

            // Long window premium: if window is long, we can afford to be more expensive
            if (windowLen >= 7 && daysAhead > 14) {
                priceMultiplier *= 1.05;
                reason += " (длинное окно)";
            }

            // Floor protection: if price - cleaning < 500, it's not worth it
            int rcPrice = (int) Math.round(basePrice * priceMultiplier);
            rcPrice = Math.max(floorPrice, Math.min(ceilPrice, rcPrice));

            // Ensure min_stay doesn't exceed window length
            minStay = Math.max(1, Math.min(minStay, windowLen));

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

    /** Find all contiguous free windows within open_ahead_days horizon */
    private List<FreeWindow> findFreeWindows(
            LocalDate from, LocalDate to,
            Set<LocalDate> bookedDates, int openAheadDays) {

        LocalDate horizon = LocalDate.now().plusDays(openAheadDays);
        List<FreeWindow> windows = new ArrayList<>();

        LocalDate windowStart = null;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            boolean free = !bookedDates.contains(d) && !d.isAfter(horizon);
            if (free && windowStart == null) {
                windowStart = d;
            } else if (!free && windowStart != null) {
                windows.add(new FreeWindow(windowStart, d.minusDays(1)));
                windowStart = null;
            }
        }
        if (windowStart != null) {
            windows.add(new FreeWindow(windowStart, horizon.isBefore(to) ? horizon : to));
        }
        return windows;
    }

    private Set<LocalDate> buildBookedDates(List<Booking> bookings, LocalDate from, LocalDate to) {
        Set<LocalDate> booked = new HashSet<>();
        for (Booking b : bookings) {
            for (LocalDate d = b.getCheckIn(); d.isBefore(b.getCheckOut()); d = d.plusDays(1)) {
                if (!d.isBefore(from) && !d.isAfter(to)) booked.add(d);
            }
        }
        return booked;
    }

    /** Load RC calendar: extract booked events and closed special_price dates */
    private CalendarState loadCalendarState(String rcObjectId, LocalDate from, LocalDate to) {
        JsonNode response = rcClient.getSpecialPrices(rcObjectId, from, to);

        if (response == null || !response.has("items") || !response.get("items").isArray()) {
            throw new IllegalStateException("RC returned empty/invalid calendar");
        }

        JsonNode apartmentNode = response.get("items").get(0);
        if (apartmentNode == null) {
            return new CalendarState(Set.of(), List.of());
        }

        Set<LocalDate> closedDates = new HashSet<>();
        List<RcBooking> rcBookings = new ArrayList<>();

        // Parse events (actual bookings from RC)
        JsonNode events = apartmentNode.path("events");
        if (events.isArray()) {
            for (JsonNode event : events) {
                String status = event.path("status").asText("");
                boolean isDeleted = event.path("is_delete").asBoolean(false);
                if (isDeleted || !"booked".equals(status)) continue;

                String beginStr = event.path("begin_date").asText(null);
                String endStr = event.path("end_date").asText(null);
                if (beginStr == null || endStr == null) continue;

                try {
                    LocalDate checkIn = LocalDate.parse(beginStr);
                    LocalDate checkOut = LocalDate.parse(endStr);
                    String guestName = event.path("client").path("fio").asText(null);
                    String phone = event.path("client").path("phone").asText(null);
                    double amount = event.path("amount").asDouble(0);
                    long rcId = event.path("id").asLong(0);
                    int sourceId = event.path("source_id").asInt(0);

                    rcBookings.add(new RcBooking(rcId, checkIn, checkOut,
                            guestName, phone, amount, sourceId));
                } catch (Exception e) {
                    log.warn("Cannot parse RC event dates: {}", e.getMessage());
                }
            }
        }

        // Parse special_prices for manually closed dates
        JsonNode specialPrices = apartmentNode.path("special_prices");
        if (specialPrices.isArray()) {
            for (JsonNode sp : specialPrices) {
                JsonNode closedNode = sp.path("closed");
                boolean closed = !closedNode.isNull() && closedNode.asBoolean(false);
                if (!closed) continue;

                String beginStr = sp.path("begin_date").asText(null);
                String endStr = sp.path("end_date").asText(null);
                if (beginStr == null || endStr == null) continue;
                try {
                    LocalDate start = LocalDate.parse(beginStr);
                    LocalDate end = LocalDate.parse(endStr);
                    for (LocalDate d = start; d.isBefore(end); d = d.plusDays(1)) {
                        closedDates.add(d);
                    }
                } catch (Exception e) {
                    log.warn("Cannot parse closed special_price dates: {}", e.getMessage());
                }
            }
        }

        log.info("RC calendar: {} events, {} closed dates", rcBookings.size(), closedDates.size());
        return new CalendarState(closedDates, rcBookings);
    }

    private int parseInt(Map<String, String> map, String key, int def) {
        try { return Integer.parseInt(map.getOrDefault(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    private double parseDouble(Map<String, String> map, String key, double def) {
        try { return Double.parseDouble(map.getOrDefault(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    // --- Records & enums ---

    public enum DayStatus { FREE, BOOKED, GAP }

    private record FreeWindow(LocalDate start, LocalDate end) {
        int lengthDays() { return (int) ChronoUnit.DAYS.between(start, end) + 1; }
    }

    public record RcBooking(
            long rcId, LocalDate checkIn, LocalDate checkOut,
            String guestName, String phone, double amount, int sourceId) {}

    private record CalendarState(Set<LocalDate> closedDates, List<RcBooking> rcBookings) {}

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
