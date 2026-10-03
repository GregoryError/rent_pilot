package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.AvailabilityService;
import ru.rentoptima.service.EffectivePriceService;
import ru.rentoptima.service.EffectivePriceService.DateUnitKey;
import ru.rentoptima.service.ProductionCalendarService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Шахматка.
 * <p>
 * Строится поверх AvailabilityService.occupancyGrid (источник правды об занятости)
 * и EffectivePriceService (источник правды о цене на день). Все CSS-классы и
 * форматированные строки собираются сервером — шаблон только подставляет
 * готовые значения, см. комментарий у предыдущей итерации.
 */
@Controller
@RequestMapping("/calendar/grid")
@RequiredArgsConstructor
public class OccupancyGridController {

    private static final int DEFAULT_DAYS = 30;
    private static final int MIN_DAYS = 7;
    private static final int MAX_DAYS = 62;

    private final PropertyRepository propertyRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final AvailabilityService availability;
    private final ProductionCalendarService prodCalendar;
    private final EffectivePriceService effectivePrice;

    @GetMapping
    public String grid(@RequestParam(required = false) String from,
                       @RequestParam(required = false) Integer days,
                       Model model) {
        Long tenantId = AuthContext.tenantId();

        LocalDate today = LocalDate.now();
        LocalDate start = parseDate(from, today);
        int span = clamp(days == null ? DEFAULT_DAYS : days, MIN_DAYS, MAX_DAYS);
        LocalDate endExclusive = start.plusDays(span);
        LocalDate lastDay = endExclusive.minusDays(1);

        List<Property> properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        Map<Long, List<UnitType>> unitTypesByProperty = new LinkedHashMap<>();
        List<Long> allUnitTypeIds = new ArrayList<>();
        for (Property p : properties) {
            List<UnitType> uts = unitTypeRepo.findByPropertyIdAndActiveTrue(p.getId());
            unitTypesByProperty.put(p.getId(), uts);
            for (UnitType ut : uts) allUnitTypeIds.add(ut.getId());
        }

        Map<Long, Map<LocalDate, Integer>> occByUnit =
                allUnitTypeIds.isEmpty()
                        ? Map.of()
                        : availability.occupancyGrid(allUnitTypeIds, start, endExclusive);

        Map<DateUnitKey, BigDecimal> prices =
                allUnitTypeIds.isEmpty()
                        ? Map.of()
                        : effectivePrice.resolveBatch(allUnitTypeIds, start, lastDay);

        Map<LocalDate, String> holidays = prodCalendar.getHolidaysInRange(start, lastDay);

        List<DayHeader> dayHeaders = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            LocalDate d = start.plusDays(i);
            int dow = d.getDayOfWeek().getValue();
            boolean weekend = dow >= 6;
            boolean holiday = holidays.containsKey(d);
            boolean isToday = d.equals(today);
            boolean past = d.isBefore(today);

            StringBuilder cls = new StringBuilder("grid__daycol");
            if (weekend)  cls.append(" is-weekend");
            if (holiday)  cls.append(" is-holiday");
            if (isToday)  cls.append(" is-today");
            if (past)     cls.append(" is-past");

            dayHeaders.add(new DayHeader(
                    d, d.getDayOfMonth(), russianDowShort(dow),
                    isToday, cls.toString()));
        }

        boolean empty = allUnitTypeIds.isEmpty();
        boolean hasProperties = !properties.isEmpty();

        List<Row> rows = new ArrayList<>();
        for (Property p : properties) {
            List<UnitType> uts = unitTypesByProperty.getOrDefault(p.getId(), List.of());
            for (int idx = 0; idx < uts.size(); idx++) {
                UnitType ut = uts.get(idx);
                int capacity = ut.getUnitCount() == null ? 1 : Math.max(1, ut.getUnitCount());
                Map<LocalDate, Integer> occ = occByUnit.getOrDefault(ut.getId(), Map.of());

                boolean firstOfProperty = idx == 0;
                String labelClasses = firstOfProperty
                        ? "grid__label grid__label--group"
                        : "grid__label";

                List<Cell> cells = new ArrayList<>(span);
                for (DayHeader h : dayHeaders) {
                    int busy = occ.getOrDefault(h.date(), 0);
                    boolean past = h.date().isBefore(today);
                    String status = statusOf(busy, capacity, past);

                    StringBuilder cellCls = new StringBuilder("grid__cell grid__cell--");
                    cellCls.append(status);
                    if (h.today()) cellCls.append(" is-today");

                    BigDecimal price = prices.get(new DateUnitKey(ut.getId(), h.date()));
                    String priceText = formatPrice(price);

                    String title = h.date() + " — занято " + busy + " из " + capacity
                            + (priceText.isEmpty() ? "" : "; план цены " + priceText);

                    cells.add(new Cell(
                            busy, capacity, capacity > 1 && busy > 0,
                            cellCls.toString(), title,
                            h.date().toString(),         // ISO date для клика → модалка
                            ut.getId(),                   // unit_type_id для модалки
                            priceText));
                }
                rows.add(new Row(
                        p.getName(), ut.getName(), capacity,
                        firstOfProperty, labelClasses, cells));
            }
        }

        model.addAttribute("activePage", "grid");
        model.addAttribute("dayHeaders", dayHeaders);
        model.addAttribute("rows", rows);
        model.addAttribute("startIso", start.toString());
        model.addAttribute("span", span);
        model.addAttribute("prevStart", start.minusDays(span));
        model.addAttribute("nextStart", start.plusDays(span));
        model.addAttribute("rangeLabel", russianRange(start, lastDay));
        model.addAttribute("empty", empty);
        model.addAttribute("hasProperties", hasProperties);
        return "pages/calendar/grid";
    }

    private static String formatPrice(BigDecimal price) {
        if (price == null) return "";
        return price.setScale(0, RoundingMode.HALF_UP).toPlainString() + "₽";
    }

    private static String russianDowShort(int dow) {
        return switch (dow) {
            case 1 -> "пн"; case 2 -> "вт"; case 3 -> "ср";
            case 4 -> "чт"; case 5 -> "пт"; case 6 -> "сб"; case 7 -> "вс";
            default -> "";
        };
    }

    private static String russianMonthGen(int m) {
        return switch (m) {
            case 1 -> "января";  case 2 -> "февраля";  case 3 -> "марта";
            case 4 -> "апреля";  case 5 -> "мая";      case 6 -> "июня";
            case 7 -> "июля";    case 8 -> "августа";  case 9 -> "сентября";
            case 10 -> "октября"; case 11 -> "ноября"; case 12 -> "декабря";
            default -> "";
        };
    }

    private static String russianRange(LocalDate from, LocalDate to) {
        String left = from.getDayOfMonth() + " " + russianMonthGen(from.getMonthValue());
        String right = to.getDayOfMonth() + " " + russianMonthGen(to.getMonthValue()) + " " + to.getYear();
        return left + " — " + right;
    }

    private static String statusOf(int busy, int capacity, boolean past) {
        if (past && busy <= 0) return "past";
        if (busy <= 0) return "free";
        if (busy >= capacity) return "full";
        return "partial";
    }

    private static LocalDate parseDate(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public record DayHeader(LocalDate date, int day, String dowShort,
                            boolean today, String cssClasses) {}

    public record Cell(int busy, int capacity, boolean showBusyBadge,
                       String cssClasses, String title,
                       String dateIso, Long unitTypeId, String priceText) {}

    public record Row(String propertyName, String unitTypeName, int capacity,
                      boolean firstOfProperty, String labelCssClasses,
                      List<Cell> cells) {}
}
