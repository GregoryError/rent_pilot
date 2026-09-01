package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PropertyRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Pricing Engine — core of the automation system.
 *
 * Runs hourly, calculates recommended price + min_stay for each future date,
 * and (in SOFT/FULL autopilot mode) pushes changes to RealtyCalendar.
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

    /** Run every hour */
//    @Scheduled(fixedDelay = 3600000)

    @Scheduled(fixedDelay = 220000)
    public void runAutopilot() {
        List<Property> properties = propertyRepo.findAll().stream()
                .filter(p -> p.getActive() && p.getRcObjectId() != null && !p.getRcObjectId().isBlank())
                .toList();

        for (Property property : properties) {
            try {
                String mode = settings.getValue(property.getTenant().getId(), "autopilot_mode");
                if ("OFF".equals(mode) || mode == null) continue;
                runForProperty(property, mode);
            } catch (Exception e) {
                log.error("Autopilot error for property {}: {}", property.getId(), e.getMessage());
            }
        }
    }

    public List<PricingRecommendation> getRecommendations(Long tenantId, Long propertyId) {
        Property property = propertyRepo.findById(propertyId).orElseThrow();
        return calculateRecommendations(property, LocalDate.now(), LocalDate.now().plusDays(60));
    }

    private void runForProperty(Property property, String mode) {
        Long tenantId = property.getTenant().getId();
        LocalDate from = LocalDate.now();
        LocalDate to = LocalDate.now().plusDays(
                settings.getIntValue(tenantId, "open_ahead_days", 30)
        );

        List<PricingRecommendation> recs = calculateRecommendations(property, from, to);


        log.info("========== RC DIAGNOSTIC GET START ==========");

        try {
            JsonNode existing = rcClient.getSpecialPrices(
                    property.getRcObjectId(),
                    from,
                    to
            );

            log.info(
                    "RC DIAGNOSTIC GET RESULT:\n{}",
                    existing == null ? "NULL" : existing.toPrettyString()
            );

        } catch (Exception e) {
            log.error(
                    "RC DIAGNOSTIC GET ERROR",
                    e
            );
        }

        log.info("========== RC DIAGNOSTIC GET END ==========");



        // Build RC special prices payload
        List<RealtyCalendarClient.SpecialPrice> items = new ArrayList<>();
        int autoDelta = settings.getIntValue(tenantId, "auto_price_delta", 50);

        for (PricingRecommendation rec : recs) {
            if (rec.status() == DayStatus.BOOKED) continue;

            // In SOFT mode — only apply small changes
            if ("SOFT".equals(mode) && Math.abs(rec.priceDelta()) > autoDelta) {
                log.debug("SOFT: skip large change for {} (delta={})", rec.date(), rec.priceDelta());
                continue;
            }

            items.add(new RealtyCalendarClient.SpecialPrice(
                    rec.date(),

                    new RealtyCalendarClient.ValueWrapper(
                            new RealtyCalendarClient.DiagnosticValue(
                                    rec.recommendedPrice()
                            )
                    ),

                    new RealtyCalendarClient.ValueWrapper(
                            new RealtyCalendarClient.DiagnosticValue(
                                    rec.recommendedMinStay()
                            )
                    ),

                    new RealtyCalendarClient.ValueWrapper(
                            new RealtyCalendarClient.DiagnosticValue(
                                    "no"
                            )
                    ),

                    null,

                    null,

                    new RealtyCalendarClient.Rates(
                            false,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of()
                    )
            ));
        }

        if (items.isEmpty()) return;

        log.info("Autopilot [{}] pushing {} price updates for property {}",
                mode, items.size(), property.getName());
        rcClient.saveSpecialPrices(property.getRcObjectId(), items);
    }

    public List<PricingRecommendation> calculateRecommendations(Property property, LocalDate from, LocalDate to) {
        Long tenantId = property.getTenant().getId();
        Map<String, String> s = settings.getSettingsMap(tenantId);

        int weekdayPrice = Integer.parseInt(s.getOrDefault("weekday_base_price", "3200"));
        int weekendPrice = Integer.parseInt(s.getOrDefault("weekend_base_price", "4200"));
        int floorPrice   = Integer.parseInt(s.getOrDefault("min_price_floor", "2500"));
        int ceilPrice    = Integer.parseInt(s.getOrDefault("max_price_ceiling", "6000"));
        int maxMinStay   = Integer.parseInt(s.getOrDefault("max_min_stay", "10"));

        List<Booking> bookings = bookingRepo.findActiveInRange(tenantId, from, to);
        var gaps = statsService.detectGaps(tenantId, from, to);
        Set<LocalDate> gapDates = new HashSet<>();
        for (var gap : gaps) {
            for (LocalDate d = gap.from(); !d.isAfter(gap.to().minusDays(1)); d = d.plusDays(1)) {
                gapDates.add(d);
            }
        }

        List<PricingRecommendation> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            final LocalDate d = date;
            long daysAhead = ChronoUnit.DAYS.between(today, d);

            // Check if booked
            boolean isBooked = bookings.stream().anyMatch(b ->
                    !d.isBefore(b.getCheckIn()) && d.isBefore(b.getCheckOut()));
            if (isBooked) {
                result.add(new PricingRecommendation(d, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, DayStatus.BOOKED, "Забронировано", 100));
                continue;
            }

            boolean isWeekend = d.getDayOfWeek().getValue() >= 5; // Fri-Sat
            boolean isHoliday = prodCalendar.isHoliday(d);
            boolean isGap = gapDates.contains(d);

            // Base price
            int basePrice = (isWeekend || isHoliday) ? weekendPrice : weekdayPrice;

            // Price modifiers based on days ahead
            double priceMultiplier = 1.0;
            String reason;
            int minStay;
            int confidence;

            if (daysAhead > 30) {
                // Far future: high min stay (soft lock), normal price
                minStay = maxMinStay;
                priceMultiplier = 1.0;
                reason = "Открытие дат за " + daysAhead + " дней";
                confidence = 60;
            } else if (daysAhead > 14) {
                minStay = isWeekend ? 2 : 5;
                priceMultiplier = 1.0;
                reason = "Стандартный период";
                confidence = 70;
            } else if (daysAhead > 7) {
                minStay = isWeekend ? 1 : 3;
                priceMultiplier = 0.97; // slight discount
                reason = "Приближаются даты, снижаем мин. срок";
                confidence = 75;
            } else if (daysAhead > 3) {
                minStay = isWeekend ? 1 : 2;
                priceMultiplier = 0.93;
                reason = "7 дней до заезда — снижаем цену";
                confidence = 80;
            } else {
                minStay = 1;
                priceMultiplier = 0.87;
                reason = "3 дня до заезда — минимальный срок и цена";
                confidence = 85;
            }

            // Gap: further discount to fill the gap
            if (isGap) {
                priceMultiplier *= 0.90;
                minStay = 1;
                reason = "Gap (дыра) — агрессивная скидка";
                confidence = 90;
            }

            // Holiday premium
            if (isHoliday) {
                priceMultiplier *= 1.10;
                reason += " + праздник";
            }

            int recommended = (int) Math.round(basePrice * priceMultiplier);
            recommended = Math.max(floorPrice, Math.min(ceilPrice, recommended));
            int delta = recommended - basePrice;

            result.add(new PricingRecommendation(
                    d,
                    BigDecimal.valueOf(basePrice),
                    BigDecimal.valueOf(recommended),
                    delta,
                    minStay,
                    isGap ? DayStatus.GAP : DayStatus.FREE,
                    reason,
                    confidence
            ));
        }

        return result;
    }

    public enum DayStatus { FREE, BOOKED, GAP }

    public record PricingRecommendation(
            LocalDate date,
            BigDecimal currentPrice,
            BigDecimal recommendedPrice,
            int priceDelta,
            int recommendedMinStay,
            DayStatus status,
            String reason,
            int confidence
    ) {}
}
