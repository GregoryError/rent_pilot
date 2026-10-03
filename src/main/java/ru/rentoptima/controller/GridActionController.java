package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.PlannedPrice;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.PlannedPriceRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.TenantRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.util.PdAnonymizer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Обработчик действий из модального окна шахматки.
 * <p>
 * Один POST-эндпоинт /calendar/grid/action принимает unified-форму:
 * оператор может одновременно (а) закрыть даты как блок/бронь, (б) задать на
 * них запланированную цену. Либо только одно, либо и то и то.
 * <p>
 * Для ручной брони типа MANUAL_BOOKING с указанной суммой — создаётся И
 * CalendarBlock (чтобы шахматка показывала занятость сразу), И Booking (чтобы
 * сумма пошла в выручку). Это дублирование осознанное: CalendarBlock —
 * «unit_type занят», Booking — «вот подробности и деньги», они живут в разных
 * ролях и обе нужны. AvailabilityService считает занятость по обоим, не удваивая.
 */
@Slf4j
@Controller
@RequestMapping("/calendar/grid/action")
@RequiredArgsConstructor
public class GridActionController {

    private final UnitTypeRepository unitTypeRepo;
    private final PropertyRepository propertyRepo;
    private final TenantRepository tenantRepo;
    private final BookingRepository bookingRepo;
    private final CalendarBlockRepository blockRepo;
    private final PlannedPriceRepository plannedPriceRepo;

    @PostMapping
    @Transactional
    public String action(
            @RequestParam Long unitTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false, defaultValue = "false") boolean createEntry,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String guestName,
            @RequestParam(required = false) String guestPhone,
            @RequestParam(required = false) Integer guestCount,
            @RequestParam(required = false) BigDecimal amount,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false, defaultValue = "false") boolean setPrice,
            @RequestParam(required = false) BigDecimal plannedPrice,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate viewFrom,
            @RequestParam(required = false) Integer viewDays,
            @RequestParam(required = false) String returnMonth,
            RedirectAttributes redirect) {

        Long tenantId = AuthContext.tenantId();

        UnitType ut = unitTypeRepo.findById(unitTypeId).orElse(null);
        if (ut == null || !tenantId.equals(ut.getTenantId())) {
            redirect.addFlashAttribute("error", "Категория не найдена");
            return "redirect:/calendar/grid";
        }
        if (toDate.isBefore(fromDate)) {
            redirect.addFlashAttribute("error", "Дата конца раньше начала");
            return backToGrid(returnMonth, viewFrom, viewDays, fromDate);
        }

        int blocksCreated = 0;
        int bookingsCreated = 0;
        int pricesSet = 0;

        if (createEntry && entryType != null && !entryType.isBlank()) {
            CalendarBlock.BlockType type;
            try {
                type = CalendarBlock.BlockType.valueOf(entryType);
            } catch (IllegalArgumentException e) {
                redirect.addFlashAttribute("error", "Неизвестный тип: " + entryType);
                return backToGrid(returnMonth, viewFrom, viewDays, fromDate);
            }

            // CalendarBlock хранит интервал как полуоткрытый [from, toExclusive).
            // В UI оператор выбирает «от-до включительно», поэтому прибавляем день.
            LocalDate toExclusive = toDate.plusDays(1);

            CalendarBlock block = new CalendarBlock();
            block.setTenantId(tenantId);
            block.setUnitTypeId(unitTypeId);
            block.setBlockType(type);
            block.setFromDate(fromDate);
            block.setToDate(toExclusive);
            block.setReason(cleanOrNull(reason));
            block.setCreatedAt(LocalDateTime.now());
            block.setUpdatedAt(LocalDateTime.now());
            blockRepo.save(block);
            blocksCreated = 1;

            // Для ручной брони заводим ещё и Booking — это источник суммы для выручки.
            // Если оператор ничего не ввёл в поля гостя/суммы — всё равно создаём
            // пустой Booking с data_source=MANUAL: наличие записи важнее, чем её полнота,
            // и позже оператор сможет её дополнить.
            if (type == CalendarBlock.BlockType.MANUAL_BOOKING) {
                Property prop = propertyRepo.findById(ut.getPropertyId()).orElse(null);
                if (prop != null) {
                    Tenant tenant = tenantRepo.getReferenceById(tenantId);
                    Property propRef = propertyRepo.getReferenceById(prop.getId());

                    Booking b = new Booking();
                    b.setTenant(tenant);
                    b.setProperty(propRef);
                    b.setUnitTypeId(unitTypeId);
                    b.setStatus("BOOKED");
                    b.setSource("manual");
                    b.setDataSource("MANUAL");
                    b.setCheckIn(fromDate);
                    b.setCheckOut(toExclusive);
                    b.setNights((int) ChronoUnit.DAYS.between(fromDate, toExclusive));
                    if (amount != null) b.setAmount(amount);
                    if (guestName != null && !guestName.isBlank()) b.setGuestName(guestName.trim());
                    if (guestPhone != null && !guestPhone.isBlank()) b.setGuestPhone(guestPhone.trim());
                    if (guestCount != null) b.setGuestCount(guestCount);
                    if (reason != null && !reason.isBlank()) b.setNotes(reason.trim());
                    b.setUpdatedAt(LocalDateTime.now());
                    bookingRepo.save(b);
                    bookingsCreated = 1;
                }
            }
        }

        if (setPrice && plannedPrice != null && plannedPrice.compareTo(BigDecimal.ZERO) > 0) {
            LocalDate d = fromDate;
            while (!d.isAfter(toDate)) {
                Optional<PlannedPrice> existing =
                        plannedPriceRepo.findByUnitTypeIdAndDate(unitTypeId, d);
                PlannedPrice pp;
                if (existing.isPresent()) {
                    pp = existing.get();
                } else {
                    pp = new PlannedPrice();
                    pp.setTenantId(tenantId);
                    pp.setUnitTypeId(unitTypeId);
                    pp.setDate(d);
                    pp.setCreatedAt(LocalDateTime.now());
                }
                pp.setPrice(plannedPrice);
                pp.setUpdatedAt(LocalDateTime.now());
                plannedPriceRepo.save(pp);
                pricesSet++;
                d = d.plusDays(1);
            }
        }

        StringBuilder msg = new StringBuilder();
        if (blocksCreated > 0) msg.append("Запись создана");
        if (bookingsCreated > 0) msg.append(" (включая запись в бронях)");
        if (pricesSet > 0) {
            if (!msg.isEmpty()) msg.append(". ");
            msg.append("Цена задана на ").append(pricesSet).append(" дн");
        }
        if (msg.isEmpty()) {
            msg.append("Ничего не изменено (отметьте, что именно нужно сделать)");
        }
        redirect.addFlashAttribute("success", msg.toString());

        log.info("Grid action: tenant={}, unitType={}, [{}..{}], blocks={}, bookings={}, prices={}",
                tenantId, unitTypeId, fromDate, toDate, blocksCreated, bookingsCreated, pricesSet);
        return backToGrid(returnMonth, viewFrom, viewDays, fromDate);
    }

    /**
     * Ручные записи категории на конкретный день — модалка показывает их с кнопкой
     * «Удалить». Импортированное с площадок сюда не попадает: такой блок вернётся
     * при следующей синхронизации, снимать его надо на самой площадке.
     */
    @GetMapping("/entries")
    @ResponseBody
    @Transactional(readOnly = true)
    public List<ManualEntry> entries(
            @RequestParam Long unitTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long tenantId = AuthContext.tenantId();
        UnitType ut = unitTypeRepo.findById(unitTypeId).orElse(null);
        if (ut == null || !tenantId.equals(ut.getTenantId())) return List.of();

        LocalDate next = date.plusDays(1);
        List<Booking> bookings = new ArrayList<>(
                bookingRepo.findManualOverlapping(tenantId, unitTypeId, date, next));
        List<ManualEntry> result = new ArrayList<>();

        for (CalendarBlock b : blockRepo.findOverlapping(unitTypeId, date, next)) {
            if (b.getChannelId() != null || !tenantId.equals(b.getTenantId())) continue;
            String details = b.getReason();
            if (b.getBlockType() == CalendarBlock.BlockType.MANUAL_BOOKING) {
                // Парная бронь показывается одной строкой вместе с блоком
                Booking pair = takePair(bookings, b);
                if (pair != null) details = bookingDetails(pair);
            }
            result.add(new ManualEntry("block", b.getId(), blockTypeLabel(b.getBlockType()),
                    b.getFromDate().toString(), b.getToDate().minusDays(1).toString(),
                    details == null ? "" : details));
        }
        for (Booking b : bookings) {
            result.add(new ManualEntry("booking", b.getId(), "Ручная бронь",
                    b.getCheckIn().toString(), b.getCheckOut().minusDays(1).toString(),
                    bookingDetails(b)));
        }
        return result;
    }

    /**
     * Удаление ручной записи. Блок удаляется физически; бронь помечается DELETED —
     * она перестаёт занимать даты и считаться в выручке, но остаётся в базе.
     */
    @PostMapping("/delete")
    @Transactional
    public String delete(
            @RequestParam String entry,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate viewFrom,
            @RequestParam(required = false) Integer viewDays,
            @RequestParam(required = false) String returnMonth,
            RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        LocalDate fallback = LocalDate.now();

        String[] parts = entry.split(":", 2);
        Long id = null;
        try {
            if (parts.length == 2) id = Long.valueOf(parts[1]);
        } catch (NumberFormatException e) {
            // id останется null — ниже общий ответ «не найдена»
        }

        boolean deleted = false;
        if (id != null && "block".equals(parts[0])) {
            CalendarBlock block = blockRepo.findById(id).orElse(null);
            if (block != null && tenantId.equals(block.getTenantId()) && block.getChannelId() == null) {
                fallback = block.getFromDate();
                if (block.getBlockType() == CalendarBlock.BlockType.MANUAL_BOOKING) {
                    List<Booking> candidates = new ArrayList<>(bookingRepo.findManualOverlapping(
                            tenantId, block.getUnitTypeId(), block.getFromDate(), block.getToDate()));
                    Booking pair = takePair(candidates, block);
                    if (pair != null) markDeleted(pair);
                }
                blockRepo.delete(block);
                deleted = true;
            }
        } else if (id != null && "booking".equals(parts[0])) {
            Booking booking = bookingRepo.findById(id).orElse(null);
            if (booking != null && tenantId.equals(booking.getTenant().getId())
                    && "MANUAL".equals(booking.getDataSource())) {
                fallback = booking.getCheckIn();
                markDeleted(booking);
                deleted = true;
            }
        }

        if (deleted) {
            redirect.addFlashAttribute("success", "Запись удалена, даты снова свободны");
            log.info("Grid delete: tenant={}, entry={}", tenantId, entry);
        } else {
            redirect.addFlashAttribute("error", "Запись не найдена или её нельзя удалить");
        }
        return backToGrid(returnMonth, viewFrom, viewDays, fallback);
    }

    private void markDeleted(Booking b) {
        b.setStatus("DELETED");
        b.setUpdatedAt(LocalDateTime.now());
        bookingRepo.save(b);
    }

    /** Находит и вынимает из списка бронь, заведённую вместе с этим блоком (те же даты). */
    private static Booking takePair(List<Booking> bookings, CalendarBlock block) {
        for (Iterator<Booking> it = bookings.iterator(); it.hasNext(); ) {
            Booking b = it.next();
            if (block.getFromDate().equals(b.getCheckIn()) && block.getToDate().equals(b.getCheckOut())) {
                it.remove();
                return b;
            }
        }
        return null;
    }

    private static String bookingDetails(Booking b) {
        List<String> parts = new ArrayList<>();
        String guest = PdAnonymizer.toInitial(b.getGuestName());
        if (guest != null) parts.add("гость " + guest);
        if (b.getAmount() != null && b.getAmount().signum() > 0) {
            parts.add(b.getAmount().setScale(0, RoundingMode.HALF_UP).toPlainString() + " ₽");
        }
        if (b.getNotes() != null && !b.getNotes().isBlank()) parts.add(b.getNotes().trim());
        return String.join(" · ", parts);
    }

    private static String blockTypeLabel(CalendarBlock.BlockType type) {
        return switch (type) {
            case MANUAL_BOOKING -> "Ручная бронь";
            case MAINTENANCE -> "Ремонт";
            case OWNER_USE -> "Личное использование";
            case HOLD -> "Hold";
            case CHANNEL_SYNC -> "Импорт с площадки";
        };
    }

    /** @param toDate последняя занятая ночь, включительно — как в форме модалки */
    public record ManualEntry(String kind, Long id, String typeLabel,
                              String fromDate, String toDate, String details) {}

    /**
     * Возврат на тот же вид шахматки, с которого открыли модалку. Без этого после
     * сохранения сетка прыгала на дату действия и сбрасывала горизонт на дефолтный.
     */
    private static String backToGrid(String returnMonth, LocalDate viewFrom, Integer viewDays,
                                     LocalDate fallbackFrom) {
        // Модалку открыли из шахматки на главной — возвращаемся на главную, в тот же месяц.
        if (returnMonth != null && !returnMonth.isBlank()) {
            try {
                return "redirect:/dashboard?m=" + YearMonth.parse(returnMonth);
            } catch (Exception e) {
                return "redirect:/dashboard";
            }
        }
        String url = "redirect:/calendar/grid?from=" + (viewFrom != null ? viewFrom : fallbackFrom);
        return viewDays != null ? url + "&days=" + viewDays : url;
    }

    private String cleanOrNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
