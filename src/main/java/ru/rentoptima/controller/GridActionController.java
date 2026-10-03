package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
            RedirectAttributes redirect) {

        Long tenantId = AuthContext.tenantId();

        UnitType ut = unitTypeRepo.findById(unitTypeId).orElse(null);
        if (ut == null || !tenantId.equals(ut.getTenantId())) {
            redirect.addFlashAttribute("error", "Категория не найдена");
            return "redirect:/calendar/grid";
        }
        if (toDate.isBefore(fromDate)) {
            redirect.addFlashAttribute("error", "Дата конца раньше начала");
            return backToGrid(viewFrom, viewDays, fromDate);
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
                return backToGrid(viewFrom, viewDays, fromDate);
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
        return backToGrid(viewFrom, viewDays, fromDate);
    }

    /**
     * Возврат на тот же вид шахматки, с которого открыли модалку. Без этого после
     * сохранения сетка прыгала на дату действия и сбрасывала горизонт на дефолтный.
     */
    private static String backToGrid(LocalDate viewFrom, Integer viewDays, LocalDate fallbackFrom) {
        String url = "redirect:/calendar/grid?from=" + (viewFrom != null ? viewFrom : fallbackFrom);
        return viewDays != null ? url + "&days=" + viewDays : url;
    }

    private String cleanOrNull(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
