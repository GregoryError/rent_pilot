package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.BookingStatsService;
import ru.rentoptima.service.ProductionCalendarService;
import ru.rentoptima.service.SettingsService;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Controller
@RequestMapping("/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final PropertyRepository propertyRepo;
    private final BookingRepository bookingRepo;
    private final BookingStatsService statsService;
    private final ProductionCalendarService prodCalendar;
    private final SettingsService settings;

    @GetMapping
    public String calendar(@RequestParam(required = false) Integer year,
                            @RequestParam(required = false) Integer month,
                            Model model) {
        Long tenantId = AuthContext.tenantId();
        LocalDate now = LocalDate.now();
        int y = year != null ? year : now.getYear();
        int m = month != null ? month : now.getMonthValue();
        YearMonth ym = YearMonth.of(y, m);

        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();

        // Get first property (for now single-property)
        List<Property> properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        Property property = properties.isEmpty() ? null : properties.get(0);

        // Build calendar grid
        List<CalendarDay> days = new ArrayList<>();
        if (property != null) {
            List<Booking> bookings = bookingRepo.findActiveInRange(tenantId, from, to);
            Map<LocalDate, String> holidays = prodCalendar.getHolidaysInRange(from, to);
            List<BookingStatsService.GapInfo> gaps = statsService.detectGaps(tenantId, from, to);
            Set<LocalDate> gapDates = new HashSet<>();
            for (var gap : gaps) {
                LocalDate d = gap.from();
                while (!d.isAfter(gap.to().minusDays(1))) {
                    gapDates.add(d);
                    d = d.plusDays(1);
                }
            }

            int weekdayPrice = settings.getIntValue(tenantId, "weekday_base_price", 3200);
            int weekendPrice = settings.getIntValue(tenantId, "weekend_base_price", 4200);

            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                final LocalDate date = d;
                Booking booking = bookings.stream()
                        .filter(b -> !date.isBefore(b.getCheckIn()) && date.isBefore(b.getCheckOut()))
                        .findFirst().orElse(null);

                boolean isWeekend = d.getDayOfWeek().getValue() >= 5; // Fri=5, Sat=6
                boolean isHoliday = holidays.containsKey(d);
                boolean isGap = gapDates.contains(d);
                boolean isToday = d.equals(now);
                boolean isPast = d.isBefore(now);
                int basePrice = (isWeekend || isHoliday) ? weekendPrice : weekdayPrice;

                String status;
                String guestName = null;
                String source = null;
                boolean isCheckIn = false;
                boolean isCheckOut = false;

                if (booking != null) {
                    status = "booked";
                    guestName = booking.getGuestName();
                    source = booking.getSource();
                    isCheckIn = date.equals(booking.getCheckIn());
                    isCheckOut = date.equals(booking.getCheckOut().minusDays(1));
                } else if (isGap) {
                    status = "gap";
                } else if (isPast) {
                    status = "past";
                } else {
                    status = "free";
                }

                days.add(new CalendarDay(
                        d, d.getDayOfMonth(), d.getDayOfWeek().getValue(),
                        status, guestName, source, basePrice,
                        isWeekend, isHoliday, isGap, isToday, isPast,
                        isCheckIn, isCheckOut,
                        holidays.getOrDefault(d, null)
                ));
            }
        }

        // Previous/next month links
        YearMonth prev = ym.minusMonths(1);
        YearMonth next = ym.plusMonths(1);

        model.addAttribute("activePage", "calendar");
        model.addAttribute("days", days);
        model.addAttribute("yearMonth", ym);
        model.addAttribute("monthName", russianMonth(m) + " " + y);
        model.addAttribute("prevYear", prev.getYear());
        model.addAttribute("prevMonth", prev.getMonthValue());
        model.addAttribute("nextYear", next.getYear());
        model.addAttribute("nextMonth", next.getMonthValue());
        model.addAttribute("property", property);

        return "pages/calendar/index";
    }

    private String russianMonth(int m) {
        return switch (m) {
            case 1 -> "Январь"; case 2 -> "Февраль"; case 3 -> "Март";
            case 4 -> "Апрель"; case 5 -> "Май"; case 6 -> "Июнь";
            case 7 -> "Июль"; case 8 -> "Август"; case 9 -> "Сентябрь";
            case 10 -> "Октябрь"; case 11 -> "Ноябрь"; case 12 -> "Декабрь";
            default -> "";
        };
    }

    public record CalendarDay(
            LocalDate date, int dayOfMonth, int dayOfWeek,
            String status, String guestName, String source, int basePrice,
            boolean weekend, boolean holiday, boolean gap, boolean today, boolean past,
            boolean checkIn, boolean checkOut, String holidayName
    ) {}
}
