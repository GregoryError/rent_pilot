package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.service.AvailabilityService.DayOccupancy;
import ru.rentoptima.service.AvailabilityService.Occupant;
import ru.rentoptima.service.EffectivePriceService.DateUnitKey;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Собирает модель шахматки для шаблона (fragments/occupancy-grid).
 * <p>
 * Вынесено из OccupancyGridController, чтобы одна и та же сетка рисовалась и на
 * /calendar/grid, и на /dashboard. Все CSS-классы и форматированные строки
 * собираются здесь — шаблон только подставляет готовые значения.
 */
@Service
@RequiredArgsConstructor
public class OccupancyGridService {

    private final PropertyRepository propertyRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final ChannelRepository channelRepo;
    private final AvailabilityService availability;
    private final ProductionCalendarService prodCalendar;
    private final EffectivePriceService effectivePrice;

    private static final String RC_STYLE = "background: " + ChannelPalette.RC_COLOR;

    public GridView build(Long tenantId, LocalDate start, int span) {
        LocalDate today = LocalDate.now();
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
        Map<Long, String> channelColors = new HashMap<>();
        List<LegendChannel> legendChannels = new ArrayList<>();
        for (Channel ch : channelRepo.findByTenantIdAndActiveTrue(tenantId)) {
            channelNames.put(ch.getId(), ch.getName());
            if (ch.getChannelType() == Channel.ChannelType.MANUAL) continue;
            String color = ChannelPalette.colorOf(ch);
            channelColors.put(ch.getId(), color);
            legendChannels.add(new LegendChannel(
                    ch.getName(), ChannelPalette.letterOf(ch.getName()), "background: " + color));
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
        boolean rcPainted = false;
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

                    // Цвет источника — только у полностью занятого дня: частично занятая
                    // категория мини-отеля остаётся «жёлтой», иначе она выглядела бы распроданной.
                    Paint paint = "full".equals(status)
                            ? paintOf(day, channelNames, channelColors) : Paint.NONE;

                    if (RC_STYLE.equals(paint.style())) rcPainted = true;

                    StringBuilder cellCls = new StringBuilder("grid__cell grid__cell--");
                    cellCls.append(status);
                    if (paint.manual())        cellCls.append(" grid__cell--manual");
                    if (paint.style() != null) cellCls.append(" grid__cell--colored");
                    if (past && busy > 0)      cellCls.append(" is-past-busy");
                    if (h.today())      cellCls.append(" is-today");
                    if (h.monthStart()) cellCls.append(" is-month-start");
                    if (conflict)       cellCls.append(" is-conflict");

                    BigDecimal price = prices.get(new DateUnitKey(ut.getId(), h.date()));

                    cells.add(new Cell(
                            busy, capacity, capacity > 1 && busy > 0,
                            // У мини-отеля на плитке счётчик занятых номеров — буква там не поместится
                            capacity > 1 ? "" : paint.letter(), paint.style(),
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

        if (rcPainted) legendChannels.add(new LegendChannel("RealtyCalendar", "R", RC_STYLE));

        return new GridView(dayHeaders, rows, conflictCount, empty, hasProperties, legendChannels);
    }

    /**
     * Чем закрашен занятый день. Ручная запись (бронь по телефону, ремонт, личное
     * использование) — чёрная плитка и важнее площадки: это то, что хост сделал сам.
     * Иначе — цвет канала первой записи с площадки; у броней из RC канала нет,
     * для них отдельный фиксированный цвет.
     */
    private static Paint paintOf(DayOccupancy day, Map<Long, String> channelNames,
                                 Map<Long, String> channelColors) {
        if (day == null || day.occupants().isEmpty()) return Paint.NONE;
        for (Occupant o : day.occupants()) {
            if (o.manual()) return Paint.MANUAL;
        }
        for (Occupant o : day.occupants()) {
            String color = o.channelId() == null ? null : channelColors.get(o.channelId());
            if (color != null) {
                return new Paint("background: " + color,
                        ChannelPalette.letterOf(channelNames.get(o.channelId())), false);
            }
        }
        return new Paint(RC_STYLE, "R", false);
    }

    /** style == null — цвет задаёт CSS-класс (свободно / частично / вручную). */
    private record Paint(String style, String letter, boolean manual) {
        static final Paint NONE = new Paint(null, "", false);
        static final Paint MANUAL = new Paint(null, "", true);
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

    public static String russianMonthShort(int m) {
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

    public static String russianMonthGen(int m) {
        return switch (m) {
            case 1 -> "января";  case 2 -> "февраля";  case 3 -> "марта";
            case 4 -> "апреля";  case 5 -> "мая";      case 6 -> "июня";
            case 7 -> "июля";    case 8 -> "августа";  case 9 -> "сентября";
            case 10 -> "октября"; case 11 -> "ноября"; case 12 -> "декабря";
            default -> "";
        };
    }

    private static String statusOf(int busy, int capacity, boolean past) {
        if (past && busy <= 0) return "past";
        if (busy <= 0) return "free";
        if (busy >= capacity) return "full";
        return "partial";
    }

    public record DayHeader(LocalDate date, int day, String dowShort,
                            boolean today, String cssClasses,
                            String monthLabel, String fullLabel, boolean monthStart) {}

    public record Cell(int busy, int capacity, boolean showBusyBadge,
                       String letter, String style,
                       String cssClasses, String info,
                       String dateIso, Long unitTypeId,
                       String priceText, String priceShort) {}

    public record Row(String propertyName, String unitTypeName, int capacity,
                      boolean firstOfProperty, String labelCssClasses,
                      List<Cell> cells) {}

    public record GridView(List<DayHeader> dayHeaders, List<Row> rows, int conflictCount,
                           boolean empty, boolean hasProperties,
                           List<LegendChannel> legendChannels) {}

    public record LegendChannel(String name, String letter, String style) {}
}
