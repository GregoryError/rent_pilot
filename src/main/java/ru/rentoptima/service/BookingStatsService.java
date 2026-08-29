package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.repository.BookingRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BookingStatsService {

    private final BookingRepository bookingRepo;
    private final SettingsService settings;

    /** Core KPIs for a date range */
    public DashboardKpi getKpi(Long tenantId, LocalDate from, LocalDate to) {
        BigDecimal revenue = bookingRepo.sumRevenueInRange(tenantId, from, to);
        if (revenue == null) revenue = BigDecimal.ZERO;

        long bookings = bookingRepo.countBookingsInRange(tenantId, from, to);
        long checkouts = bookingRepo.countCheckoutsInRange(tenantId, from, to);
        Double avgNights = bookingRepo.avgNightsInRange(tenantId, from, to);
        Long totalNights = bookingRepo.sumNightsInRange(tenantId, from, to);

        long daysInPeriod = ChronoUnit.DAYS.between(from, to) + 1;
        double occupancy = totalNights != null ? (double) totalNights / daysInPeriod * 100 : 0;

        int cleaningCost = settings.getIntValue(tenantId, "cleaning_cost", 1400);
        BigDecimal totalCleaning = BigDecimal.valueOf(checkouts * cleaningCost);

        // Net RevPAR: (revenue - cleaning) / days in period
        BigDecimal netRevPar = daysInPeriod > 0
                ? revenue.subtract(totalCleaning).divide(BigDecimal.valueOf(daysInPeriod), 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new DashboardKpi(
                revenue,
                bookings,
                checkouts,
                avgNights != null ? Math.round(avgNights * 100) / 100.0 : 0,
                Math.round(occupancy * 10) / 10.0,
                totalCleaning,
                netRevPar
        );
    }

    /** Detect gaps (single free days between bookings) */
    public List<GapInfo> detectGaps(Long tenantId, LocalDate from, LocalDate to) {
        List<Booking> bookings = bookingRepo.findActiveInRange(tenantId, from, to);
        List<GapInfo> gaps = new ArrayList<>();

        for (int i = 0; i < bookings.size() - 1; i++) {
            LocalDate currentCheckOut = bookings.get(i).getCheckOut();
            LocalDate nextCheckIn = bookings.get(i + 1).getCheckIn();
            long gapDays = ChronoUnit.DAYS.between(currentCheckOut, nextCheckIn);

            if (gapDays >= 1 && gapDays <= 2) {
                gaps.add(new GapInfo(
                        currentCheckOut,
                        nextCheckIn,
                        (int) gapDays,
                        bookings.get(i).getGuestName(),
                        bookings.get(i + 1).getGuestName()
                ));
            }
        }
        return gaps;
    }

    /** Booking pace: how filled is the next month vs historical average */
    public BookingPace getBookingPace(Long tenantId) {
        LocalDate now = LocalDate.now();
        LocalDate nextMonthStart = now.plusMonths(1).withDayOfMonth(1);
        LocalDate nextMonthEnd = nextMonthStart.plusMonths(1).minusDays(1);
        int daysInMonth = nextMonthEnd.getDayOfMonth();

        Long bookedNights = bookingRepo.sumNightsInRange(tenantId, nextMonthStart, nextMonthEnd);
        double currentOccupancy = bookedNights != null ? (double) bookedNights / daysInMonth * 100 : 0;

        // Compare with same period historically (rough: average of last 12 months occupancy)
        double historicalAvg = 0;
        int monthsCounted = 0;
        for (int m = 1; m <= 12; m++) {
            LocalDate mStart = now.minusMonths(m).withDayOfMonth(1);
            LocalDate mEnd = mStart.plusMonths(1).minusDays(1);
            Long nights = bookingRepo.sumNightsInRange(tenantId, mStart, mEnd);
            if (nights != null && nights > 0) {
                historicalAvg += (double) nights / mEnd.getDayOfMonth() * 100;
                monthsCounted++;
            }
        }
        if (monthsCounted > 0) historicalAvg /= monthsCounted;

        double paceRatio = historicalAvg > 0 ? currentOccupancy / historicalAvg : 0;

        String status;
        if (paceRatio > 1.2) status = "AHEAD";
        else if (paceRatio > 0.8) status = "NORMAL";
        else status = "BEHIND";

        return new BookingPace(
                nextMonthStart.getMonth().toString(),
                Math.round(currentOccupancy * 10) / 10.0,
                Math.round(historicalAvg * 10) / 10.0,
                status
        );
    }

    /** Action items for "what to do today" */
    public List<ActionItem> getActionItems(Long tenantId) {
        List<ActionItem> items = new ArrayList<>();
        LocalDate now = LocalDate.now();

        // Upcoming checkouts (next 2 days)
        List<Booking> checkouts = bookingRepo.findActiveInRange(tenantId, now, now.plusDays(2));
        for (Booking b : checkouts) {
            if (b.getCheckOut().equals(now) || b.getCheckOut().equals(now.plusDays(1))) {
                items.add(new ActionItem(
                        "CLEANING",
                        "Выезд " + (b.getCheckOut().equals(now) ? "сегодня" : "завтра"),
                        b.getGuestName() != null ? b.getGuestName() : "Гость",
                        b.getCheckOut().toString()
                ));
            }
        }

        // Gaps in next 14 days
        List<GapInfo> gaps = detectGaps(tenantId, now, now.plusDays(14));
        for (GapInfo gap : gaps) {
            items.add(new ActionItem(
                    "GAP",
                    "Пустое окно " + gap.gapDays + " д.",
                    gap.from + " — " + gap.to,
                    "Рекомендация: открыть за сниженную цену"
            ));
        }

        // Booking pace warning
        BookingPace pace = getBookingPace(tenantId);
        if ("BEHIND".equals(pace.status())) {
            items.add(new ActionItem(
                    "PACE",
                    "Загрузка отстаёт",
                    pace.month() + ": " + pace.currentOccupancy() + "% vs " + pace.historicalAvg() + "% (норма)",
                    "Рассмотрите снижение цен или мин. срока"
            ));
        }

        return items;
    }

    // --- Records ---

    public record DashboardKpi(
            BigDecimal revenue,
            long bookings,
            long checkouts,
            double avgNights,
            double occupancy,
            BigDecimal cleaningCost,
            BigDecimal netRevPar
    ) {}

    public record GapInfo(LocalDate from, LocalDate to, int gapDays, String beforeGuest, String afterGuest) {}

    public record BookingPace(String month, double currentOccupancy, double historicalAvg, String status) {}

    public record ActionItem(String type, String title, String detail, String recommendation) {}
}
