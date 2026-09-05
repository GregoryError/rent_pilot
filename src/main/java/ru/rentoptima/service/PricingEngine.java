package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Scheduled(fixedDelay = 3600000) // every hour
    public void runAutopilot() {
        List<Property> properties = propertyRepo.findAll().stream()
                .filter(p -> p.getActive()
                        && p.getRcObjectId() != null
                        && !p.getRcObjectId().isBlank())
                .toList();

        for (Property property : properties) {
            try {
                Long tenantId = property.getTenant().getId();
                String mode = settings.getValue(tenantId, "autopilot_mode");
                if (mode == null || "OFF".equals(mode)) continue;
                runForProperty(property, mode);
            } catch (Exception e) {
                log.error("Autopilot error for property {}: {}", property.getName(), e.getMessage());
            }
        }
    }

    public List<PricingRecommendation> getRecommendations(Long tenantId, Long propertyId) {
        Property property = propertyRepo.findById(propertyId).orElseThrow();
        return calculateRecommendations(property, LocalDate.now(), LocalDate.now().plusDays(60));
    }

    private void runForProperty(Property property, String mode) {
        Long tenantId = property.getTenant().getId();
        int openAheadDays = settings.getIntValue(tenantId, "open_ahead_days", 30);
        int autoDelta = settings.getIntValue(tenantId, "auto_price_delta", 50);

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(openAheadDays);

        List<PricingRecommendation> recs = calculateRecommendations(property, from, to);

        List<RealtyCalendarClient.SpecialPrice> items = new ArrayList<>();

        for (PricingRecommendation rec : recs) {
            if (rec.status() == DayStatus.BOOKED) continue;

            // SOFT: skip changes larger than autoDelta
            if ("SOFT".equals(mode) && Math.abs(rec.priceDelta()) > autoDelta) {
                log.debug("SOFT: skip large change for {} (delta={}₽)", rec.date(), rec.priceDelta());
                continue;
            }

            items.add(new RealtyCalendarClient.SpecialPrice(
                    rec.date(),
                    rec.recommendedPrice(),
                    rec.recommendedMinStay()
            ));
        }

        if (items.isEmpty()) {
            log.info("Autopilot [{}]: no items to push for {}", mode, property.getName());
            return;
        }

        log.info("Autopilot [{}]: pushing {} price updates for {}", mode, items.size(), property.getName());
        rcClient.saveSpecialPrices(property.getRcObjectId(), items);
    }

    public List<PricingRecommendation> calculateRecommendations(Property property, LocalDate from, LocalDate to) {
        Long tenantId = property.getTenant().getId();
        Map<String, String> s = settings.getSettingsMap(tenantId);

        int weekdayBase  = parseInt(s, "weekday_base_price", 3200);
        int weekendBase  = parseInt(s, "weekend_base_price", 4200);
        int floorPrice   = parseInt(s, "min_price_floor", 2500);
        int ceilPrice    = parseInt(s, "max_price_ceiling", 6000);
        int maxMinStay   = parseInt(s, "max_min_stay", 10);
        int cleaningCost = parseInt(s, "cleaning_cost", 1400);
        double markupPct = Double.parseDouble(s.getOrDefault("platform_markup_pct", "18")) / 100.0;

        List<Booking> bookings = bookingRepo.findActiveInRange(tenantId, from, to);
        List<BookingStatsService.GapInfo> gaps = statsService.detectGaps(tenantId, from, to);

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

            // Skip booked days
            boolean isBooked = bookings.stream().anyMatch(b ->
                    !d.isBefore(b.getCheckIn()) && d.isBefore(b.getCheckOut()));
            if (isBooked) {
                result.add(new PricingRecommendation(
                        d, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0,
                        BigDecimal.ZERO, DayStatus.BOOKED, "Забронировано", 100));
                continue;
            }

            boolean isWeekend = d.getDayOfWeek().getValue() >= 5; // Fri=5, Sat=6
            boolean isHoliday = prodCalendar.isHoliday(d);
            boolean isGap = gapDates.contains(d);

            int basePrice = (isWeekend || isHoliday) ? weekendBase : weekdayBase;

            double multiplier;
            int minStay;
            String reason;
            int confidence;

            if (daysAhead > 30) {
                multiplier = 1.0; minStay = maxMinStay;
                reason = "Далеко (" + daysAhead + " дн.) — soft lock";
                confidence = 55;
            } else if (daysAhead > 14) {
                multiplier = 1.0; minStay = isWeekend ? 2 : 5;
                reason = "Стандартный период";
                confidence = 65;
            } else if (daysAhead > 7) {
                multiplier = 0.97; minStay = isWeekend ? 1 : 3;
                reason = "2 недели — снижаем мин. срок";
                confidence = 72;
            } else if (daysAhead > 3) {
                multiplier = 0.93; minStay = isWeekend ? 1 : 2;
                reason = "Неделя — снижаем цену и мин. срок";
                confidence = 80;
            } else {
                multiplier = 0.87; minStay = 1;
                reason = "3 дня — минимум";
                confidence = 88;
            }

            if (isGap) {
                multiplier *= 0.90; minStay = 1;
                reason = "Gap — агрессивная скидка";
                confidence = 92;
            }

            if (isHoliday) {
                multiplier *= 1.10;
                reason += " + праздник";
            }

            // RC price — what we set in RC (without platform markup)
            int rcPrice = (int) Math.round(basePrice * multiplier);
            rcPrice = Math.max(floorPrice, Math.min(ceilPrice, rcPrice));

            // Guest price = RC price * (1 + markup)
            // Net profit = guest price / (1 + markup) - cleaning/nights (for 1 night)
            // For profitability: minimum viable RC price where net > 0
            BigDecimal guestPrice = BigDecimal.valueOf(rcPrice)
                    .multiply(BigDecimal.valueOf(1 + markupPct))
                    .setScale(0, RoundingMode.HALF_UP);

            // Net per night (assuming 1 night for conservatism)
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
        try { return Integer.parseInt(map.getOrDefault(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public enum DayStatus { FREE, BOOKED, GAP }

    public record PricingRecommendation(
            LocalDate date,
            BigDecimal currentPrice,
            BigDecimal recommendedPrice,
            int priceDelta,
            int recommendedMinStay,
            BigDecimal netPerNight,      // RC price minus cleaning cost
            DayStatus status,
            String reason,
            int confidence
    ) {}
}
