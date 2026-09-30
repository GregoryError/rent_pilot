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
import ru.rentoptima.service.ProductionCalendarService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Шахматка — визуализация занятости всех unit_type тенанта на горизонте дней.
 * <p>
 * Строится строго поверх {@link AvailabilityService#occupancyGrid}, чтобы
 * источник правды о занятости был один для iCal-фидов и для UI. Если завтра
 * поменяется логика «что считать занятым» (например, тентативные брони),
 * достаточно поправить AvailabilityService — шахматка обновится сама.
 * <p>
 * Локализация подписей (месяцы, дни недели) — вручную, как и в
 * {@code CalendarController}: Thymeleaf-i18n в проекте не настроен,
 * и договорённость такая же.
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

        // Все объекты + категории тенанта, в порядке property → unit_types.
        List<Property> properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);
        Map<Long, List<UnitType>> unitTypesByProperty = new LinkedHashMap<>();
        List<Long> allUnitTypeIds = new ArrayList<>();
        for (Property p : properties) {
            List<UnitType> uts = unitTypeRepo.findByPropertyIdAndActiveTrue(p.getId());
            unitTypesByProperty.put(p.getId(), uts);
            for (UnitType ut : uts) allUnitTypeIds.add(ut.getId());
        }

        // Один запрос на всю сетку — важнее, чем красота кода: с 5-6 unit_type
        // и 30 днями это 150-200 ячеек, ходить в БД по каждой мы не будем.
        Map<Long, Map<LocalDate, Integer>> occByUnit =
                allUnitTypeIds.isEmpty()
                        ? Map.of()
                        : availability.occupancyGrid(allUnitTypeIds, start, endExclusive);

        Map<LocalDate, String> holidays = prodCalendar.getHolidaysInRange(start, lastDay);

        // Заголовки колонок.
        List<DayHeader> dayHeaders = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            LocalDate d = start.plusDays(i);
            int dow = d.getDayOfWeek().getValue();
            dayHeaders.add(new DayHeader(
                    d, d.getDayOfMonth(), dow, russianDowShort(dow),
                    dow >= 6,
                    holidays.containsKey(d),
                    d.equals(today),
                    d.isBefore(today)
            ));
        }

        // Ряды: сохраняем группировку по property (первый ряд группы рисует заголовок объекта).
        List<Row> rows = new ArrayList<>();
        for (Property p : properties) {
            List<UnitType> uts = unitTypesByProperty.getOrDefault(p.getId(), List.of());
            for (int idx = 0; idx < uts.size(); idx++) {
                UnitType ut = uts.get(idx);
                int capacity = ut.getUnitCount() == null ? 1 : Math.max(1, ut.getUnitCount());
                Map<LocalDate, Integer> occ = occByUnit.getOrDefault(ut.getId(), Map.of());

                List<Cell> cells = new ArrayList<>(span);
                for (DayHeader h : dayHeaders) {
                    int busy = occ.getOrDefault(h.date(), 0);
                    cells.add(new Cell(
                            h.date(), busy, capacity,
                            statusOf(busy, capacity, h.past()),
                            h.today()
                    ));
                }
                rows.add(new Row(
                        p.getId(), p.getName(),
                        ut.getId(), ut.getName(), capacity,
                        idx == 0,
                        cells
                ));
            }
        }

        model.addAttribute("activePage", "grid");
        model.addAttribute("dayHeaders", dayHeaders);
        model.addAttribute("rows", rows);
        model.addAttribute("start", start);
        model.addAttribute("startIso", start.toString());
        model.addAttribute("lastDay", lastDay);
        model.addAttribute("span", span);
        model.addAttribute("prevStart", start.minusDays(span));
        model.addAttribute("nextStart", start.plusDays(span));
        model.addAttribute("today", today);
        model.addAttribute("rangeLabel", russianRange(start, lastDay));
        model.addAttribute("empty", allUnitTypeIds.isEmpty());
        model.addAttribute("hasProperties", !properties.isEmpty());
        return "pages/calendar/grid";
    }

    /* Локализованные форматтеры — намеренно без i18n, см. class javadoc. */

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

    public record DayHeader(LocalDate date, int day, int dayOfWeek,
                            String dowShort,
                            boolean weekend, boolean holiday,
                            boolean today, boolean past) {}

    public record Cell(LocalDate date, int busy, int capacity, String status, boolean today) {
        public boolean multiUnit() { return capacity > 1; }
        public boolean hasBusy() { return busy > 0; }
    }

    public record Row(Long propertyId, String propertyName,
                      Long unitTypeId, String unitTypeName, int capacity,
                      boolean firstOfProperty, List<Cell> cells) {}
}