package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BookingStatsService {

    /**
     * Темп бронирований сравнивается с нормой, только если в истории есть столько полных
     * месяцев (с первой брони). Норма — среднее по месяцам: из одного-двух месяцев она
     * получается случайной, и любой тихий месяц выглядел бы «отставанием». Три месяца —
     * не меньше 90 дней истории.
     */
    public static final int PACE_MIN_FULL_MONTHS = 3;
    /** Статус темпа, когда сравнивать не с чем: нет квартир, истории или броней в ней. */
    public static final String PACE_NO_DATA = "NO_DATA";

    private static final Locale RU = Locale.forLanguageTag("ru");

    private final BookingRepository bookingRepo;
    private final SettingsService settings;
    private final PropertyRepository propertyRepo;
    private final UnitTypeRepository unitTypeRepo;

    /** Сколько квартир (единиц) у тенанта: категории активных объектов, с их количеством. */
    public int units(Long tenantId) {
        Set<Long> properties = new HashSet<>();
        for (Property p : propertyRepo.findByTenantIdAndActiveTrue(tenantId)) properties.add(p.getId());
        int units = 0;
        for (UnitType ut : unitTypeRepo.findByTenantIdAndActiveTrue(tenantId)) {
            if (!properties.contains(ut.getPropertyId())) continue;
            units += ut.getUnitCount() == null || ut.getUnitCount() < 1 ? 1 : ut.getUnitCount();
        }
        return units;
    }

    /** Название месяца для заголовка, в именительном падеже: «Ноябрь». */
    public static String monthName(YearMonth month) {
        String name = month.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, RU);
        return name.substring(0, 1).toUpperCase(RU) + name.substring(1);
    }

    /** Core KPIs for a date range */
    public DashboardKpi getKpi(Long tenantId, LocalDate from, LocalDate to) {
        // Get all bookings that overlap with the range
        List<Booking> bookings = bookingRepo.findActiveInRangeForTenant(tenantId, from, to);

        long daysInPeriod = ChronoUnit.DAYS.between(from, to) + 1;

        // Count nights that actually fall INTO the range (not total booking length)
        long nightsInPeriod = 0;
        long bookingsCount = 0;
        long checkoutsCount = 0;
        BigDecimal revenue = BigDecimal.ZERO;
        double totalNightsForAvg = 0;

        for (Booking b : bookings) {
            // Skip closures without amount (amount=0) from revenue but keep for occupancy
            boolean isManualClosure = b.getAmount() == null
                    || b.getAmount().compareTo(BigDecimal.ZERO) == 0;

            // Overlap of [checkIn, checkOut) with [from, to+1)
            LocalDate overlapStart = b.getCheckIn().isBefore(from) ? from : b.getCheckIn();
            LocalDate overlapEnd = b.getCheckOut().isAfter(to.plusDays(1)) ? to.plusDays(1) : b.getCheckOut();
            long overlap = ChronoUnit.DAYS.between(overlapStart, overlapEnd);
            if (overlap <= 0) continue;

            nightsInPeriod += overlap;

            // Count booking if checkIn is within range
            if (!b.getCheckIn().isBefore(from) && !b.getCheckIn().isAfter(to)) {
                bookingsCount++;
                totalNightsForAvg += b.getNights() != null ? b.getNights() : 0;
                if (!isManualClosure && b.getAmount() != null) {
                    revenue = revenue.add(b.getAmount());
                }
            }
            // Count checkout if checkOut is within range
            if (!b.getCheckOut().isBefore(from) && !b.getCheckOut().isAfter(to.plusDays(1))
                    && !isManualClosure) {
                checkoutsCount++;
            }
        }

        // Ночей в календаре — дни периода × квартиры: с двумя квартирами занятость не 200%
        long capacity = daysInPeriod * units(tenantId);
        double occupancy = capacity > 0 ? Math.min(100.0, (double) nightsInPeriod / capacity * 100) : 0;
        double avgNights = bookingsCount > 0 ? totalNightsForAvg / bookingsCount : 0;

        int cleaningCost = settings.getIntValue(tenantId, "cleaning_cost", 1400);
        BigDecimal totalCleaning = BigDecimal.valueOf(checkoutsCount * cleaningCost);

        // Чистый доход на ночь в календаре: (выручка − уборка) / все ночи квартир за период
        BigDecimal netRevPar = capacity > 0
                ? revenue.subtract(totalCleaning).divide(BigDecimal.valueOf(capacity), 0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new DashboardKpi(
                revenue,
                bookingsCount,
                checkoutsCount,
                Math.round(avgNights * 100) / 100.0,
                Math.round(occupancy * 10) / 10.0,
                totalCleaning,
                netRevPar
        );
    }

    /** Короткие окна (1–2 свободных дня) между бронями одной квартиры — по всем квартирам тенанта. */
    public List<GapInfo> detectGaps(Long tenantId, LocalDate from, LocalDate to) {
        return detectGaps(tenantId, null, from, to);
    }

    /**
     * Короткие окна между бронями. Брони сравниваются только внутри своей квартиры (категории;
     * для старых броней без категории — объекта): окно «между» бронями двух разных квартир — не окно.
     *
     * @param propertyId только этот объект; null — все
     */
    public List<GapInfo> detectGaps(Long tenantId, Long propertyId, LocalDate from, LocalDate to) {
        Map<String, List<Booking>> byUnit = new LinkedHashMap<>();
        for (Booking b : bookingRepo.findActiveInRangeForTenant(tenantId, from, to)) {
            Long property = b.getProperty() == null ? null : b.getProperty().getId();
            if (propertyId != null && !propertyId.equals(property)) continue;
            String key = b.getUnitTypeId() != null ? "u" + b.getUnitTypeId() : "p" + property;
            byUnit.computeIfAbsent(key, k -> new ArrayList<>()).add(b);
        }
        List<GapInfo> gaps = new ArrayList<>();
        for (List<Booking> unit : byUnit.values()) {
            unit.sort(Comparator.comparing(Booking::getCheckIn));
            for (int i = 0; i < unit.size() - 1; i++) {
                LocalDate currentCheckOut = unit.get(i).getCheckOut();
                LocalDate nextCheckIn = unit.get(i + 1).getCheckIn();
                long gapDays = ChronoUnit.DAYS.between(currentCheckOut, nextCheckIn);
                if (gapDays >= 1 && gapDays <= 2) {
                    gaps.add(new GapInfo(currentCheckOut, nextCheckIn, (int) gapDays,
                            unit.get(i).getGuestName(), unit.get(i + 1).getGuestName()));
                }
            }
        }
        gaps.sort(Comparator.comparing(GapInfo::from));
        return gaps;
    }

    /**
     * Темп бронирований: насколько уже занят следующий месяц по сравнению с обычной занятостью
     * прошлых месяцев. Норма — среднее по всем полным месяцам с первой брони (не больше 12),
     * включая месяцы без броней. Пока таких месяцев меньше {@link #PACE_MIN_FULL_MONTHS} или
     * норма нулевая — статус {@link #PACE_NO_DATA}: сравнивать не с чем, алерта нет.
     * <p>
     * Оценка грубая: следующий месяц ещё набирается, а прошлые сравниваются целиком.
     */
    public BookingPace getBookingPace(Long tenantId) {
        LocalDate now = LocalDate.now();
        YearMonth next = YearMonth.from(now).plusMonths(1);
        int units = units(tenantId);
        double current = occupancy(tenantId, next, units);
        String month = monthName(next);

        LocalDate first = bookingRepo.findFirstCheckIn(tenantId);
        if (units == 0 || first == null) return new BookingPace(month, round(current), 0, PACE_NO_DATA);
        YearMonth start = first.getDayOfMonth() == 1 ? YearMonth.from(first) : YearMonth.from(first).plusMonths(1);
        YearMonth last = YearMonth.from(now).minusMonths(1);
        if (start.isBefore(last.minusMonths(11))) start = last.minusMonths(11);
        long months = ChronoUnit.MONTHS.between(start, last) + 1;
        if (months < PACE_MIN_FULL_MONTHS) return new BookingPace(month, round(current), 0, PACE_NO_DATA);

        double sum = 0;
        for (YearMonth m = start; !m.isAfter(last); m = m.plusMonths(1)) sum += occupancy(tenantId, m, units);
        double historicalAvg = sum / months;
        if (historicalAvg <= 0) return new BookingPace(month, round(current), 0, PACE_NO_DATA);

        double paceRatio = current / historicalAvg;
        String status;
        if (paceRatio > 1.2) status = "AHEAD";
        else if (paceRatio > 0.8) status = "NORMAL";
        else status = "BEHIND";
        return new BookingPace(month, round(current), round(historicalAvg), status);
    }

    /** Занятость месяца, %: ночи броней внутри месяца / (дни × квартиры), не больше 100. */
    private double occupancy(Long tenantId, YearMonth month, int units) {
        if (units <= 0) return 0;
        LocalDate from = month.atDay(1);
        LocalDate toExclusive = month.plusMonths(1).atDay(1);
        long nights = 0;
        for (Booking b : bookingRepo.findActiveInRangeForTenant(tenantId, from, month.atEndOfMonth())) {
            LocalDate s = b.getCheckIn().isBefore(from) ? from : b.getCheckIn();
            LocalDate e = b.getCheckOut().isAfter(toExclusive) ? toExclusive : b.getCheckOut();
            nights += Math.max(0, ChronoUnit.DAYS.between(s, e));
        }
        return Math.min(100.0, nights * 100.0 / ((long) month.lengthOfMonth() * units));
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Action items for "what to do today" */
    public List<ActionItem> getActionItems(Long tenantId) {
        List<ActionItem> items = new ArrayList<>();
        LocalDate now = LocalDate.now();

        // Upcoming checkouts (next 2 days)
        List<Booking> checkouts = bookingRepo.findActiveInRangeForTenant(tenantId, now, now.plusDays(2));
        for (Booking b : checkouts) {
            if (b.getCheckOut().equals(now) || b.getCheckOut().equals(now.plusDays(1))) {
                items.add(new ActionItem(
                        "CLEANING",
                        "Выезд " + (b.getCheckOut().equals(now) ? "сегодня" : "завтра"),
                        b.getGuestName() != null ? b.getGuestName() : "Гость",
                        null
                ));
            }
        }

        // Gaps in next 14 days
        List<GapInfo> gaps = detectGaps(tenantId, now, now.plusDays(14));
        for (GapInfo gap : gaps) {
            items.add(new ActionItem(
                    "GAP",
                    "Пустое окно " + gap.gapDays + " д.",
                    "Свободно: " + AlertSchedulerService.formatNights(gap.from, gap.to),
                    "Рекомендация: открыть за сниженную цену"
            ));
        }

        // Booking pace warning
        BookingPace pace = getBookingPace(tenantId);
        if ("BEHIND".equals(pace.status())) {
            items.add(new ActionItem(
                    "PACE",
                    "Загрузка отстаёт",
                    String.format(RU, "%s: занято %.1f %%, обычно %.1f %%", pace.month(), pace.currentOccupancy(),
                            pace.historicalAvg()),
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
