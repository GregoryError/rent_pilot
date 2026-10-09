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
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.ManualBlockEcho;
import ru.rentoptima.entity.PlannedPrice;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.ManualBlockEchoRepository;
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
import java.util.Map;
import java.util.HashMap;
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
    private final ChannelRepository channelRepo;
    private final ManualBlockEchoRepository echoRepo;
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
     * Записи категории на конкретный день для модалки: ручные — с кнопкой «Удалить»,
     * импортированные с площадок — в свёрнутом блоке «Устранить блокировку» с кнопкой
     * «Открыть даты». Удалить блок площадки нельзя (вернётся при следующей
     * синхронизации), поэтому его помечают ignored.
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

        Map<Long, String> channelNames = new HashMap<>();
        for (Channel ch : channelRepo.findByUnitTypeIdAndActiveTrue(unitTypeId)) {
            channelNames.put(ch.getId(), ch.getName());
        }

        for (CalendarBlock b : blockRepo.findOverlapping(unitTypeId, date, next)) {
            if (!tenantId.equals(b.getTenantId())) continue;
            if (b.getChannelId() != null) {
                // hold по заявке с виджета снимается в «Заявках», а не здесь
                if (b.getBlockType() != CalendarBlock.BlockType.CHANNEL_SYNC) continue;
                boolean ignored = Boolean.TRUE.equals(b.getIgnored());
                result.add(new ManualEntry(ignored ? "channel-ignored" : "channel", b.getId(),
                        "С площадки: " + channelNames.getOrDefault(b.getChannelId(), "канал"),
                        b.getFromDate().toString(), b.getToDate().minusDays(1).toString(),
                        ignored ? (b.getShadowOfManualId() != null
                                        ? "Копия ручной записи — скрыта, даты свободны"
                                        : "Вы открыли эти даты — блокировка не учитывается")
                                : b.getShadowOfManualId() != null
                                ? "Те же даты, что у ручной записи, — вероятно, её копия на площадке"
                                : "Закрыто в календаре площадки"));
                continue;
            }
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
     * Удаление ручной записи. Блок удаляется мягко (cancelled_at): занятостью он больше
     * не считается, но ещё 90 дней уходит в iCal-экспорт со STATUS:CANCELLED и держит
     * свои эхо-связи — иначе копия, оставшаяся у площадки, вернулась бы к нам
     * блокировкой «от канала». Бронь помечается DELETED — перестаёт занимать даты и
     * считаться в выручке, но остаётся в базе.
     * <p>
     * «Тени» записи (блокировки каналов с shadow_of_manual_id) физически не удаляются.
     * Тень без признаков настоящей брони ({@link #realBookingSign}) скрывается
     * автоматически — ей ставится ignored, как кнопкой «Открыть даты»: хост удалил
     * бронь и ждёт, что даты освободятся. Вернуть её можно там же, «Закрыть снова».
     * Тень с признаками настоящей брони остаётся закрывать даты, и хост получает
     * предупреждение — только в этом случае.
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
        List<String> keptShadows = List.of();
        List<String> echoChannels = List.of();
        if (id != null && "block".equals(parts[0])) {
            CalendarBlock block = blockRepo.findById(id).orElse(null);
            if (block != null && tenantId.equals(block.getTenantId()) && block.getChannelId() == null
                    && block.getCancelledAt() == null) {
                fallback = block.getFromDate();
                if (block.getBlockType() == CalendarBlock.BlockType.MANUAL_BOOKING) {
                    List<Booking> candidates = new ArrayList<>(bookingRepo.findManualOverlapping(
                            tenantId, block.getUnitTypeId(), block.getFromDate(), block.getToDate()));
                    Booking pair = takePair(candidates, block);
                    if (pair != null) markDeleted(pair);
                }
                echoChannels = echoChannelNames(tenantId, block.getId());
                keptShadows = releaseShadows(tenantId, block.getId());
                block.setCancelledAt(LocalDateTime.now());
                block.setUpdatedAt(LocalDateTime.now());
                blockRepo.save(block);
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
            if (keptShadows.isEmpty()) {
                redirect.addFlashAttribute("success", "Запись удалена");
            } else {
                redirect.addFlashAttribute("success",
                        "Запись удалена, но даты остаются закрытыми бронью с площадки");
                redirect.addFlashAttribute("echoWarning", "Внимание: "
                        + String.join("; ", keptShadows)
                        + ". Проверьте, что это не настоящая бронь. Пока она есть на площадке, даты"
                        + " в шахматке остаются закрытыми. Если это копия удалённой записи — откройте"
                        + " день и снимите её в «Устранить блокировку».");
            }
            log.info("Grid delete: tenant={}, entry={}, echoChannels={}, keptShadows={}",
                    tenantId, entry, echoChannels, keptShadows.size());
        } else {
            redirect.addFlashAttribute("error", "Запись не найдена или её нельзя удалить");
        }
        return backToGrid(returnMonth, viewFrom, viewDays, fallback);
    }

    /**
     * «Открыть даты» / «Закрыть снова» для блокировки, пришедшей с площадки.
     * Влияет только на нас: день становится свободным в шахматке и перестаёт уходить
     * в экспорт другим площадкам. На самой площадке-источнике даты остаются закрытыми.
     */
    @PostMapping("/toggle-channel-block")
    @Transactional
    public String toggleChannelBlock(
            @RequestParam String entry,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate viewFrom,
            @RequestParam(required = false) Integer viewDays,
            @RequestParam(required = false) String returnMonth,
            RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        LocalDate fallback = LocalDate.now();

        Long id = null;
        String[] parts = entry.split(":", 2);
        try {
            if (parts.length == 2) id = Long.valueOf(parts[1]);
        } catch (NumberFormatException e) {
            // id останется null
        }
        CalendarBlock block = id == null ? null : blockRepo.findById(id).orElse(null);
        if (block == null || !tenantId.equals(block.getTenantId()) || block.getChannelId() == null
                || block.getBlockType() != CalendarBlock.BlockType.CHANNEL_SYNC) {
            redirect.addFlashAttribute("error", "Блокировка не найдена");
            return backToGrid(returnMonth, viewFrom, viewDays, fallback);
        }

        boolean open = !Boolean.TRUE.equals(block.getIgnored());
        block.setIgnored(open);
        block.setUpdatedAt(LocalDateTime.now());
        blockRepo.save(block);
        log.info("Grid channel block {}: tenant={}, block={}", open ? "opened" : "closed", tenantId, id);
        redirect.addFlashAttribute("success", open
                ? "Даты открыты. На самой площадке они остаются закрытыми — откройте их и там"
                : "Блокировка площадки снова учитывается");
        return backToGrid(returnMonth, viewFrom, viewDays, block.getFromDate());
    }

    /**
     * Скрывает тени удаляемой ручной записи, в которых нет признаков настоящей брони.
     *
     * @return описания теней, оставленных закрывать даты, — для предупреждения хосту
     */
    private List<String> releaseShadows(Long tenantId, Long manualBlockId) {
        List<String> kept = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        for (CalendarBlock shadow : blockRepo.findByShadowOfManualId(manualBlockId)) {
            if (!tenantId.equals(shadow.getTenantId()) || Boolean.TRUE.equals(shadow.getIgnored())) continue;
            String sign = realBookingSign(shadow);
            if (sign == null) {
                shadow.setIgnored(true);
                shadow.setUpdatedAt(LocalDateTime.now());
                blockRepo.save(shadow);
                log.info("Grid delete: shadow block {} (channel={}, uid={}) of manual block {} auto-ignored",
                        shadow.getId(), shadow.getChannelId(), shadow.getExternalUid(), manualBlockId);
                continue;
            }
            String channel = names.computeIfAbsent(shadow.getChannelId(), id -> channelRepo.findById(id)
                    .filter(c -> tenantId.equals(c.getTenantId())).map(Channel::getName).orElse("канал"));
            kept.add("на площадке «" + channel + "» есть похожая бронь " + sign);
            log.info("Grid delete: shadow block {} (channel={}, uid={}) of manual block {} kept: {}",
                    shadow.getId(), shadow.getChannelId(), shadow.getExternalUid(), manualBlockId, sign);
        }
        return kept;
    }

    /**
     * Признак того, что тень — настоящая бронь, а не копия нашей записи: за ней стоит
     * бронь с именем гостя либо её UID не похож на технический идентификатор.
     *
     * @return пояснение для хоста или null, если признаков нет
     */
    private String realBookingSign(CalendarBlock shadow) {
        String guest = shadow.getExternalUid() == null ? null
                : bookingRepo.findByChannelIdAndExternalId(shadow.getChannelId(), shadow.getExternalUid())
                        .map(Booking::getGuestName).filter(n -> !n.isBlank())
                        .map(PdAnonymizer::toInitial).orElse(null);
        if (guest != null) return "с именем гостя " + guest;
        if (!looksTechnicalUid(shadow.getExternalUid())) return "с необычным идентификатором";
        return null;
    }

    private static final java.util.regex.Pattern TECHNICAL_UID = java.util.regex.Pattern.compile(
            "\\d+|[0-9a-fA-F]{6,}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * UID — безликий идентификатор (число, hex-хэш, UUID), каким площадки помечают и
     * импортированные у нас блокировки. Часть после «@» — домен площадки, не учитывается.
     */
    static boolean looksTechnicalUid(String uid) {
        if (uid == null || uid.isBlank()) return false;
        int at = uid.indexOf('@');
        String id = (at > 0 ? uid.substring(0, at) : uid).trim();
        return TECHNICAL_UID.matcher(id).matches();
    }

    /** Названия каналов, с которых эта ручная запись возвращалась к нам эхом. */
    private List<String> echoChannelNames(Long tenantId, Long blockId) {
        List<Long> channelIds = echoRepo.findByManualBlockId(blockId).stream()
                .map(ManualBlockEcho::getChannelId).distinct().toList();
        if (channelIds.isEmpty()) return List.of();
        return channelRepo.findAllById(channelIds).stream()
                .filter(c -> tenantId.equals(c.getTenantId()))
                .map(Channel::getName).sorted().toList();
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
        // Шахматка была открыта «от сегодня» (viewFrom пуст) — возвращаемся в тот же режим
        if (viewFrom == null && viewDays != null) {
            return "redirect:/calendar/grid?days=" + viewDays;
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
