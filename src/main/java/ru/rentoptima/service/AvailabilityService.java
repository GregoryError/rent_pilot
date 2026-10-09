package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.channel.ical.ICalWriter;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.CalendarBlockRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Единый источник правды о занятости категории номеров.
 * <p>
 * Занятость складывается из двух независимых источников: броней
 * ({@code bookings}, деньги и гость) и блокировок ({@code calendar_blocks},
 * просто «занято»). Раньше шахматка знала только про брони — из-за этого
 * ремонт или бронь по телефону не защищали даты от продажи на площадке.
 * <p>
 * Модель учитывает {@code unit_count}: у мини-отеля категория «Стандарт» с
 * восемью номерами остаётся доступной, пока занято меньше восьми. Для квартиры
 * с {@code unit_count = 1} это вырождается в привычное «занято/свободно».
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final BookingRepository bookingRepo;
    private final CalendarBlockRepository blockRepo;

    /**
     * Отдавать ли удалённые ручные записи в экспорт со STATUS:CANCELLED. Выключатель
     * на случай площадки, которая STATUS не читает и закрывает даты по отменённому
     * событию: APP_ICAL_EXPORT_CANCELLED=false.
     */
    @Value("${app.ical.export-cancelled:true}")
    private boolean exportCancelled = true;

    /**
     * Сколько единиц категории занято в каждую дату окна [from, to).
     * Даты без занятости в карте отсутствуют.
     *
     * @param excludeChannelId если не null, блокировки этого канала не считаются.
     *                         Нужно для iCal-экспорта: нельзя отдавать площадке
     *                         обратно ту занятость, которую она же нам и прислала,
     *                         иначе возникает самоподдерживающаяся блокировка,
     *                         которую потом невозможно снять.
     */
    @Transactional(readOnly = true)
    public Map<LocalDate, Integer> occupancyByDate(Long unitTypeId,
                                                    LocalDate from,
                                                    LocalDate to,
                                                    Long excludeChannelId) {
        Map<LocalDate, Integer> occupancy = new TreeMap<>();
        List<Long> ids = List.of(unitTypeId);

        for (Booking b : bookingRepo.findActiveByUnitTypesInRange(ids, from, to)) {
            addRange(occupancy, b.getCheckIn(), b.getCheckOut(), from, to);
        }
        for (CalendarBlock b : withoutActiveShadows(blockRepo.findByUnitTypesInRange(ids, from, to))) {
            if (excludeChannelId != null && excludeChannelId.equals(b.getChannelId())) continue;
            addRange(occupancy, b.getFromDate(), b.getToDate(), from, to);
        }
        return occupancy;
    }

    /**
     * Даты, в которые категория распродана полностью (занято >= unit_count).
     * Именно они уходят во внешний iCal-фид.
     */
    @Transactional(readOnly = true)
    public List<LocalDate> soldOutDates(UnitType unitType,
                                        LocalDate from,
                                        LocalDate to,
                                        Long excludeChannelId) {
        int capacity = unitType.getUnitCount() == null ? 1 : Math.max(1, unitType.getUnitCount());
        Map<LocalDate, Integer> occ =
                occupancyByDate(unitType.getId(), from, to, excludeChannelId);

        List<LocalDate> result = new ArrayList<>();
        for (Map.Entry<LocalDate, Integer> e : occ.entrySet()) {
            if (e.getValue() >= capacity) result.add(e.getKey());
        }
        return result;
    }

    /** Домен собственных UID в iCal-экспорте. */
    static final String UID_DOMAIN = "optirent.ru";

    /**
     * События для iCal-экспорта категории.
     * <p>
     * Для категории с одной единицей (квартира) — запись в запись: одна блокировка или
     * бронь = один VEVENT со своими датами и стабильным UID. Соседние записи не
     * склеиваются: площадка должна видеть те же брони, что и мы, а не один сплошной
     * интервал, у которого UID и границы меняются при каждой новой брони по соседству.
     * <p>
     * Для категории с несколькими единицами (мини-отель) так нельзя: отдельная запись
     * там не означает «продано» — продано, только когда заняты все номера. Поэтому там
     * отдаются распроданные ночи, склеенные в интервалы ({@link #busyPeriods}); удалённые
     * ручные записи со STATUS:CANCELLED туда не попадают — у интервала нет UID записи.
     */
    @Transactional(readOnly = true)
    public List<ICalWriter.BusyPeriod> exportEvents(UnitType unitType,
                                                     LocalDate from,
                                                     LocalDate to,
                                                     Long excludeChannelId) {
        int capacity = unitType.getUnitCount() == null ? 1 : Math.max(1, unitType.getUnitCount());
        if (capacity > 1) {
            return busyPeriods(unitType, from, to, excludeChannelId).stream()
                    .map(p -> new ICalWriter.BusyPeriod(
                            p.uid() + "@" + UID_DOMAIN, p.from(), p.to(), p.summary()))
                    .toList();
        }
        List<Long> ids = List.of(unitType.getId());
        return buildExportEvents(
                bookingRepo.findActiveByUnitTypesInRange(ids, from, to),
                blockRepo.findByUnitTypesInRange(ids, from, to),
                exportCancelled ? blockRepo.findCancelledByUnitTypesInRange(ids, from, to) : List.of(),
                excludeChannelId);
    }

    /**
     * Одна запись — одно событие, без склейки.
     * <ul>
     *   <li>записи канала {@code excludeChannelId} не отдаются (anti-echo);</li>
     *   <li>тень живой ручной записи не отдаётся — те же даты уже закрыты самой
     *       записью ({@link #withoutActiveShadows});</li>
     *   <li>ручная бронь хранится парой «блокировка + бронь» с одинаковыми датами —
     *       в фид идёт только блокировка;</li>
     *   <li>UID ручной записи — {@code optirent-manual-<id>@optirent.ru}: по этому
     *       маркеру импорт узнаёт собственное эхо, вернувшееся от площадки
     *       (см. ICalChannelAdapter);</li>
     *   <li>UID блокировки с канала — её {@code external_uid}; UID брони —
     *       {@code booking-<id>@optirent.ru}. Если внешний UID в фиде уже встречался
     *       (два канала прислали одинаковый), повторный заменяется на
     *       {@code block-<id>@optirent.ru}: одинаковые UID в одном календаре
     *       площадки схлопывают в одно событие;</li>
     *   <li>удалённые ручные записи ({@code cancelledBlocks}) идут с тем же UID и
     *       STATUS:CANCELLED — площадка, которая это понимает, сама снимет блокировку.</li>
     * </ul>
     */
    static List<ICalWriter.BusyPeriod> buildExportEvents(List<Booking> bookings,
                                                          List<CalendarBlock> blocks,
                                                          Long excludeChannelId) {
        return buildExportEvents(bookings, blocks, List.of(), excludeChannelId);
    }

    static List<ICalWriter.BusyPeriod> buildExportEvents(List<Booking> bookings,
                                                          List<CalendarBlock> blocks,
                                                          List<CalendarBlock> cancelledBlocks,
                                                          Long excludeChannelId) {
        List<ICalWriter.BusyPeriod> events = new ArrayList<>();
        Set<String> usedUids = new HashSet<>();
        Map<ManualKey, Integer> manualBlocks = new HashMap<>();

        for (CalendarBlock b : withoutActiveShadows(blocks)) {
            if (b.getFromDate() == null || b.getToDate() == null) continue;
            if (excludeChannelId != null && excludeChannelId.equals(b.getChannelId())) continue;
            if (b.getChannelId() == null && b.getBlockType() == CalendarBlock.BlockType.MANUAL_BOOKING) {
                manualBlocks.merge(new ManualKey(b.getUnitTypeId(), b.getFromDate(), b.getToDate()),
                        1, Integer::sum);
            }
            String own = b.isHandMade() ? manualUid(b) : "block-" + b.getId() + "@" + UID_DOMAIN;
            String external = b.getExternalUid();
            String uid = external != null && !external.isBlank() && !usedUids.contains(external)
                    ? external : own;
            usedUids.add(uid);
            events.add(new ICalWriter.BusyPeriod(uid, b.getFromDate(), b.getToDate(), "Занято"));
        }

        for (Booking b : bookings) {
            if (b.getCheckIn() == null || b.getCheckOut() == null) continue;
            if (excludeChannelId != null && excludeChannelId.equals(b.getChannelId())) continue;
            if (isManual(b)) {
                ManualKey key = new ManualKey(b.getUnitTypeId(), b.getCheckIn(), b.getCheckOut());
                Integer left = manualBlocks.get(key);
                if (left != null && left > 0) {
                    manualBlocks.put(key, left - 1);
                    continue;
                }
            }
            events.add(new ICalWriter.BusyPeriod("booking-" + b.getId() + "@" + UID_DOMAIN,
                    b.getCheckIn(), b.getCheckOut(), "Занято"));
        }

        for (CalendarBlock b : cancelledBlocks) {
            if (b.getFromDate() == null || b.getToDate() == null || !b.isHandMade()) continue;
            events.add(new ICalWriter.BusyPeriod(manualUid(b), b.getFromDate(), b.getToDate(),
                    "Отменено", true));
        }

        events.sort(Comparator.comparing(ICalWriter.BusyPeriod::from)
                .thenComparing(ICalWriter.BusyPeriod::uid));
        return events;
    }

    private static String manualUid(CalendarBlock b) {
        return CalendarBlock.MANUAL_UID_PREFIX + b.getId() + "@" + UID_DOMAIN;
    }

    /**
     * Распроданные ночи, схлопнутые в непрерывные интервалы. Только для категорий
     * с несколькими единицами — см. {@link #exportEvents}.
     */
    @Transactional(readOnly = true)
    public List<ICalWriter.BusyPeriod> busyPeriods(UnitType unitType,
                                                    LocalDate from,
                                                    LocalDate to,
                                                    Long excludeChannelId) {
        List<LocalDate> dates = soldOutDates(unitType, from, to, excludeChannelId);
        return mergeToPeriods(dates, unitType.getId());
    }

    /** Склейка отсортированного списка дат в полуоткрытые интервалы [start, end). */
    static List<ICalWriter.BusyPeriod> mergeToPeriods(List<LocalDate> sortedDates, Long unitTypeId) {
        List<ICalWriter.BusyPeriod> periods = new ArrayList<>();
        if (sortedDates.isEmpty()) return periods;

        LocalDate start = sortedDates.get(0);
        LocalDate prev = start;

        for (int i = 1; i < sortedDates.size(); i++) {
            LocalDate d = sortedDates.get(i);
            if (d.equals(prev.plusDays(1))) {
                prev = d;
                continue;
            }
            periods.add(period(unitTypeId, start, prev));
            start = d;
            prev = d;
        }
        periods.add(period(unitTypeId, start, prev));
        return periods;
    }

    private static ICalWriter.BusyPeriod period(Long unitTypeId, LocalDate start, LocalDate lastNight) {
        // DTEND в iCal не включителен — это дата выезда, т.е. последняя ночь + 1
        LocalDate end = lastNight.plusDays(1);
        String uid = "ut%d-%s".formatted(unitTypeId, start);
        return new ICalWriter.BusyPeriod(uid, start, end, "Занято");
    }

    /**
     * Сводка занятости по нескольким категориям сразу — для шахматки.
     * Ключ внешней карты — unitTypeId.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<LocalDate, Integer>> occupancyGrid(List<Long> unitTypeIds,
                                                             LocalDate from,
                                                             LocalDate to) {
        Map<Long, Map<LocalDate, Integer>> grid = new HashMap<>();
        if (unitTypeIds.isEmpty()) return grid;

        for (Long id : unitTypeIds) grid.put(id, new TreeMap<>());

        for (Booking b : bookingRepo.findActiveByUnitTypesInRange(unitTypeIds, from, to)) {
            Map<LocalDate, Integer> m = grid.get(b.getUnitTypeId());
            if (m != null) addRange(m, b.getCheckIn(), b.getCheckOut(), from, to);
        }
        for (CalendarBlock b : withoutActiveShadows(blockRepo.findByUnitTypesInRange(unitTypeIds, from, to))) {
            Map<LocalDate, Integer> m = grid.get(b.getUnitTypeId());
            if (m != null) addRange(m, b.getFromDate(), b.getToDate(), from, to);
        }
        return grid;
    }

    /**
     * То же, что {@link #occupancyGrid}, но с расшифровкой «кто занимает день» —
     * для hover-строки шахматки и индикатора конфликтов.
     * <p>
     * В отличие от occupancyGrid, не удваивает ручную бронь: GridActionController
     * заводит на неё и CalendarBlock, и Booking с одинаковыми датами, здесь такая
     * пара считается одной занятой единицей.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<LocalDate, DayOccupancy>> occupancyDetails(List<Long> unitTypeIds,
                                                                     LocalDate from,
                                                                     LocalDate to) {
        if (unitTypeIds.isEmpty()) return new HashMap<>();
        return buildDetails(unitTypeIds,
                bookingRepo.findActiveByUnitTypesInRange(unitTypeIds, from, to),
                blockRepo.findByUnitTypesInRange(unitTypeIds, from, to),
                from, to);
    }

    static Map<Long, Map<LocalDate, DayOccupancy>> buildDetails(List<Long> unitTypeIds,
                                                                 List<Booking> bookings,
                                                                 List<CalendarBlock> blocks,
                                                                 LocalDate from,
                                                                 LocalDate to) {
        Map<Long, Map<LocalDate, DayOccupancy>> grid = new HashMap<>();
        for (Long id : unitTypeIds) grid.put(id, new TreeMap<>());

        // Ручные брони, для которых ещё не встретился парный MANUAL_BOOKING-блок.
        Map<ManualKey, Integer> unpairedManual = new HashMap<>();

        for (Booking b : bookings) {
            Map<LocalDate, DayOccupancy> m = grid.get(b.getUnitTypeId());
            if (m == null) continue;
            boolean manual = isManual(b);
            if (manual) {
                unpairedManual.merge(
                        new ManualKey(b.getUnitTypeId(), b.getCheckIn(), b.getCheckOut()),
                        1, Integer::sum);
            }
            addOccupant(m, new Occupant(true, manual, b.getChannelId(), null, b.getDataSource(),
                            "booking:" + b.getId(), b.getCheckIn(), nightlyAmount(b), b.getCreatedAt()),
                    b.getCheckIn(), b.getCheckOut(), from, to);
        }
        for (CalendarBlock b : withoutActiveShadows(blocks)) {
            Map<LocalDate, DayOccupancy> m = grid.get(b.getUnitTypeId());
            if (m == null) continue;
            boolean manual = b.getChannelId() == null;
            if (manual && b.getBlockType() == CalendarBlock.BlockType.MANUAL_BOOKING) {
                ManualKey key = new ManualKey(b.getUnitTypeId(), b.getFromDate(), b.getToDate());
                Integer left = unpairedManual.get(key);
                if (left != null && left > 0) {
                    unpairedManual.put(key, left - 1);
                    continue;
                }
            }
            addOccupant(m, new Occupant(false, manual, b.getChannelId(), b.getBlockType(), null,
                            "block:" + b.getId(), b.getFromDate(), null, b.getCreatedAt()),
                    b.getFromDate(), b.getToDate(), from, to);
        }
        return grid;
    }

    /**
     * Убирает «тени» живых ручных записей — блокировки каналов с
     * {@code shadow_of_manual_id}, чья ручная запись есть в этом же списке (см. V27).
     * <p>
     * Из пары «ручная запись + её тень» остаётся ручная: на ней сумма и гость, её
     * можно удалить, и в экспорт она идёт под UID-маркером. Тень на те же даты ничего
     * не добавляет, а посчитанная вместе с ручной даёт двойную занятость и ложный
     * конфликт «ручная + площадка». Когда ручную запись удаляют, она пропадает из
     * выборки (cancelled_at), и тень начинает учитываться как обычная блокировка канала.
     */
    static List<CalendarBlock> withoutActiveShadows(List<CalendarBlock> blocks) {
        Set<Long> liveManualIds = new HashSet<>();
        boolean hasShadows = false;
        for (CalendarBlock b : blocks) {
            if (b.getShadowOfManualId() != null) hasShadows = true;
            else if (b.isHandMade() && b.getCancelledAt() == null && b.getId() != null) {
                liveManualIds.add(b.getId());
            }
        }
        if (!hasShadows) return blocks;
        return blocks.stream()
                .filter(b -> b.getShadowOfManualId() == null
                        || !liveManualIds.contains(b.getShadowOfManualId()))
                .toList();
    }

    /** Бронь завёл человек: явный MANUAL либо импорт из таблицы без канала и RC-привязки. */
    private static boolean isManual(Booking b) {
        if (b.getDataSource() != null) return "MANUAL".equals(b.getDataSource());
        return b.getChannelId() == null && b.getRcBookingId() == null;
    }

    /** Сумма брони в пересчёте на ночь; null, если суммы нет (iCal, закрытие, бронь без цены). */
    private static BigDecimal nightlyAmount(Booking b) {
        if (b.getAmount() == null || b.getAmount().signum() <= 0) return null;
        if (b.getCheckIn() == null || b.getCheckOut() == null) return null;
        long nights = ChronoUnit.DAYS.between(b.getCheckIn(), b.getCheckOut());
        if (nights <= 0) return null;
        return b.getAmount().divide(BigDecimal.valueOf(nights), 2, RoundingMode.HALF_UP);
    }

    private static void addOccupant(Map<LocalDate, DayOccupancy> target, Occupant occupant,
                                    LocalDate start, LocalDate end,
                                    LocalDate windowFrom, LocalDate windowTo) {
        if (start == null || end == null) return;
        LocalDate d = start.isBefore(windowFrom) ? windowFrom : start;
        LocalDate stop = end.isAfter(windowTo) ? windowTo : end;
        while (d.isBefore(stop)) {
            target.computeIfAbsent(d, k -> new DayOccupancy(new ArrayList<>()))
                    .occupants().add(occupant);
            d = d.plusDays(1);
        }
    }

    private record ManualKey(Long unitTypeId, LocalDate from, LocalDate to) {}

    /**
     * Одна занятая единица категории в конкретный день.
     *
     * @param booking    true — Booking, false — CalendarBlock
     * @param manual     завёл человек в UI (а не пришло с площадки/RC)
     * @param blockType  тип блокировки; null для броней
     * @param dataSource bookings.data_source; null для блокировок
     * @param key        стабильный идентификатор записи ("booking:12" / "block:7") —
     *                   чтобы считать брони, а не занятые ночи
     * @param start      первая ночь записи (может лежать раньше окна)
     * @param nightlyAmount сумма на ночь; null, если денег в записи нет
     * @param seenAt     когда запись появилась у нас
     */
    public record Occupant(boolean booking, boolean manual, Long channelId,
                           CalendarBlock.BlockType blockType, String dataSource,
                           String key, LocalDate start, BigDecimal nightlyAmount,
                           java.time.LocalDateTime seenAt) {

        /**
         * Запись — продажа без известной суммы: её выручку оцениваем по плановой цене.
         * Ремонт, личное использование, hold и «ручные закрытия» из RC деньгами не считаем.
         */
        public boolean estimatedSale() {
            if (nightlyAmount != null) return false;
            if (booking) return !"RC".equals(dataSource);
            return blockType == CalendarBlock.BlockType.CHANNEL_SYNC
                    || blockType == CalendarBlock.BlockType.MANUAL_BOOKING;
        }
    }

    public record DayOccupancy(List<Occupant> occupants) {

        public int busy() {
            return occupants.size();
        }

        /**
         * Конфликт — день занят сверх вместимости, и среди занимающих есть и ручная
         * запись, и пришедшая извне. Пересечение двух внешних каналов конфликтом
         * не считаем: площадки отдают в своём фиде и то, что сами импортировали
         * у нас, так что это почти всегда эхо, а не двойная продажа.
         */
        public boolean conflict(int capacity) {
            if (occupants.size() <= capacity) return false;
            boolean manual = false;
            boolean external = false;
            for (Occupant o : occupants) {
                if (o.manual()) manual = true;
                else external = true;
            }
            return manual && external;
        }
    }

    /** Инкрементирует счётчик занятости по каждой ночи интервала, обрезая по окну. */
    private static void addRange(Map<LocalDate, Integer> target,
                                 LocalDate start, LocalDate end,
                                 LocalDate windowFrom, LocalDate windowTo) {
        if (start == null || end == null) return;
        LocalDate d = start.isBefore(windowFrom) ? windowFrom : start;
        LocalDate stop = end.isAfter(windowTo) ? windowTo : end;
        while (d.isBefore(stop)) {
            target.merge(d, 1, Integer::sum);
            d = d.plusDays(1);
        }
    }
}
