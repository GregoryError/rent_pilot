package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.AvailabilityService;
import ru.rentoptima.service.AvailabilityService.DayOccupancy;
import ru.rentoptima.service.AvailabilityService.Occupant;
import ru.rentoptima.service.EffectivePriceService;
import ru.rentoptima.service.EffectivePriceService.DateUnitKey;
import ru.rentoptima.service.ProductionCalendarService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Шахматка.
 * <p>
 * Строится поверх AvailabilityService.occupancyDetails (источник правды об занятости)
 * и EffectivePriceService (источник правды о цене на день). Все CSS-классы и
 * форматированные строки собираются сервером — шаблон только подставляет
 * готовые значения, см. комментарий у предыдущей итерации.
 */
@Controller
@RequestMapping("/calendar/grid")
@RequiredArgsConstructor
public class OccupancyGridController {

    private static final int DEFAULT_DAYS = 30;
    private static final int MIN_DAYS = 1;
    /** Не продуктовый лимит, а защита от from=…&days=100000: год с запасом. */
    private static final int MAX_DAYS = 366;
    private static final int[] SPAN_PRESETS = {7, 14, 30, 60, 90, 180, 365};

    private final PropertyRepository propertyRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final ChannelRepository channelRepo;
    private final AvailabilityService availability;
    private final ProductionCalendarService prodCalendar;
    private final EffectivePriceService effectivePrice;

    @GetMapping
    public String grid(@RequestParam(required = false) String from,
                       @RequestParam(required = false) Integer days,
                       @RequestParam(required = false) String to,
                       Model model) {
        Long tenantId = AuthContext.tenantId();

        LocalDate today = LocalDate.now();
        LocalDate start = parseDate(from, today);
        // «to» (включительно) приходит из формы произвольного периода и важнее days.
        LocalDate toInclusive = parseDate(to, null);
        int requested = toInclusive != null
                ? (int) Math.min(MAX_DAYS, ChronoUnit.DAYS.between(start, toInclusive) + 1)
                : (days == null ? DEFAULT_DAYS : days);
        int span = clamp(requested, MIN_DAYS, MAX_DAYS);
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

        Map<Long, Map<LocalDate, DayOccupancy>> occByUnit =
                allUnitTypeIds.isEmpty()
                        ? Map.of()
                        : availability.occupancyDetails(allUnitTypeIds, start, endExclusive);

        Map<DateUnitKey, BigDecimal> prices =
                allUnitTypeIds.isEmpty()
                        ? Map.of()
                        : effectivePrice.resolveBatch(allUnitTypeIds, start, lastDay);

        Map<Long, String> channelNames = new HashMap<>();
        for (Channel ch : channelRepo.findByTenantIdAndActiveTrue(tenantId)) {
            channelNames.put(ch.getId(), ch.getName());
        }

        Map<LocalDate, String> holidays = prodCalendar.getHolidaysInRange(start, lastDay);

        List<DayHeader> dayHeaders = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            LocalDate d = start.plusDays(i);
            int dow = d.getDayOfWeek().getValue();
            boolean weekend = dow >= 6;
            boolean holiday = holidays.containsKey(d);
            boolean isToday = d.equals(today);
            boolean past = d.isBefore(today);
            boolean monthStart = i > 0 && d.getDayOfMonth() == 1;

            StringBuilder cls = new StringBuilder("grid__daycol");
            if (weekend)    cls.append(" is-weekend");
            if (holiday)    cls.append(" is-holiday");
            if (isToday)    cls.append(" is-today");
            if (past)       cls.append(" is-past");
            if (monthStart) cls.append(" is-month-start");

            // На длинном горизонте без подписи месяца числа 1..31 не читаются.
            String monthLabel = (i == 0 || monthStart) ? russianMonthShort(d.getMonthValue()) : "";
            String fullLabel = russianDowShort(dow) + ", " + d.getDayOfMonth() + " "
                    + russianMonthGen(d.getMonthValue()) + " " + d.getYear();

            dayHeaders.add(new DayHeader(
                    d, d.getDayOfMonth(), russianDowShort(dow),
                    isToday, cls.toString(), monthLabel, fullLabel, monthStart));
        }

        boolean empty = allUnitTypeIds.isEmpty();
        boolean hasProperties = !properties.isEmpty();

        int conflictCount = 0;
        List<Row> rows = new ArrayList<>();
        for (Property p : properties) {
            List<UnitType> uts = unitTypesByProperty.getOrDefault(p.getId(), List.of());
            for (int idx = 0; idx < uts.size(); idx++) {
                UnitType ut = uts.get(idx);
                int capacity = ut.getUnitCount() == null ? 1 : Math.max(1, ut.getUnitCount());
                Map<LocalDate, DayOccupancy> occ = occByUnit.getOrDefault(ut.getId(), Map.of());

                boolean firstOfProperty = idx == 0;
                String labelClasses = firstOfProperty
                        ? "grid__label grid__label--group"
                        : "grid__label";

                List<Cell> cells = new ArrayList<>(span);
                for (DayHeader h : dayHeaders) {
                    DayOccupancy day = occ.get(h.date());
                    int busy = day == null ? 0 : day.busy();
                    boolean past = h.date().isBefore(today);
                    String status = statusOf(busy, capacity, past);
                    // Прошедшие пересечения уже не исправить — не шумим красным.
                    boolean conflict = !past && day != null && day.conflict(capacity);
                    if (conflict) conflictCount++;

                    StringBuilder cellCls = new StringBuilder("grid__cell grid__cell--");
                    cellCls.append(status);
                    if (h.today())      cellCls.append(" is-today");
                    if (h.monthStart()) cellCls.append(" is-month-start");
                    if (conflict)       cellCls.append(" is-conflict");

                    BigDecimal price = prices.get(new DateUnitKey(ut.getId(), h.date()));

                    cells.add(new Cell(
                            busy, capacity, capacity > 1 && busy > 0,
                            cellCls.toString(),
                            infoOf(day, busy, capacity, past, conflict, channelNames),
                            h.date().toString(),         // ISO date для клика → модалка
                            ut.getId(),                   // unit_type_id для модалки
                            formatPrice(price),
                            formatPriceShort(price)));
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
        model.addAttribute("endIso", lastDay.toString());
        model.addAttribute("span", span);
        model.addAttribute("spanOptions", spanOptions(span));
        model.addAttribute("prevStart", start.minusDays(span));
        model.addAttribute("nextStart", start.plusDays(span));
        model.addAttribute("rangeLabel", russianRange(start, lastDay));
        model.addAttribute("conflictCount", conflictCount);
        model.addAttribute("empty", empty);
        model.addAttribute("hasProperties", hasProperties);
        return "pages/calendar/grid";
    }

    /** Пресеты горизонта + текущее значение, если оно в пресеты не попало. */
    private static List<SpanOption> spanOptions(int span) {
        List<SpanOption> options = new ArrayList<>();
        boolean matched = false;
        for (int preset : SPAN_PRESETS) {
            if (!matched && span < preset) {
                options.add(new SpanOption(span, span + " " + pluralDays(span), true));
                matched = true;
            }
            if (preset == span) matched = true;
            options.add(new SpanOption(preset, preset + " " + pluralDays(preset), preset == span));
        }
        if (!matched) options.add(new SpanOption(span, span + " " + pluralDays(span), true));
        return options;
    }

    private static String pluralDays(int n) {
        int mod100 = n % 100;
        int mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return "дней";
        if (mod10 == 1) return "день";
        if (mod10 >= 2 && mod10 <= 4) return "дня";
        return "дней";
    }

    /** Текст для hover-строки: занятость и кто именно занимает день. */
    private static String infoOf(DayOccupancy day, int busy, int capacity, boolean past,
                                 boolean conflict, Map<Long, String> channelNames) {
        if (busy <= 0) return past ? "прошедший день" : "свободно";

        Set<String> sources = new LinkedHashSet<>();
        for (Occupant o : day.occupants()) sources.add(sourceLabel(o, channelNames));

        String text = "занято " + busy + " из " + capacity + ": " + String.join(", ", sources);
        return conflict ? text + " — конфликт, ручная запись пересекается с площадкой" : text;
    }

    private static String sourceLabel(Occupant o, Map<Long, String> channelNames) {
        if (o.manual()) {
            if (o.booking() || o.blockType() == null) return "ручная бронь";
            return switch (o.blockType()) {
                case MAINTENANCE -> "ремонт";
                case OWNER_USE -> "личное использование";
                case HOLD -> "hold";
                default -> "ручная бронь";
            };
        }
        String channel = o.channelId() == null ? null : channelNames.get(o.channelId());
        if (channel != null) return channel;
        if ("RC".equals(o.dataSource())) return "RealtyCalendar";
        return o.booking() ? "бронь с площадки" : "iCal-канал";
    }

    private static String formatPrice(BigDecimal price) {
        if (price == null) return "";
        return price.setScale(0, RoundingMode.HALF_UP).toPlainString() + "₽";
    }

    /** Для плитки: без знака рубля, иначе пятизначная цена не влезает в 30px. */
    private static String formatPriceShort(BigDecimal price) {
        if (price == null) return "";
        return price.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String russianMonthShort(int m) {
        return switch (m) {
            case 1 -> "янв";  case 2 -> "фев";  case 3 -> "мар";
            case 4 -> "апр";  case 5 -> "май";  case 6 -> "июн";
            case 7 -> "июл";  case 8 -> "авг";  case 9 -> "сен";
            case 10 -> "окт"; case 11 -> "ноя"; case 12 -> "дек";
            default -> "";
        };
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
        String left = from.getDayOfMonth() + " " + russianMonthGen(from.getMonthValue())
                + (from.getYear() == to.getYear() ? "" : " " + from.getYear());
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
                            boolean today, String cssClasses,
                            String monthLabel, String fullLabel, boolean monthStart) {}

    public record Cell(int busy, int capacity, boolean showBusyBadge,
                       String cssClasses, String info,
                       String dateIso, Long unitTypeId,
                       String priceText, String priceShort) {}

    public record SpanOption(int days, String label, boolean selected) {}

    public record Row(String propertyName, String unitTypeName, int capacity,
                      boolean firstOfProperty, String labelCssClasses,
                      List<Cell> cells) {}
}
