package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.TenantRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.service.EffectivePriceService.DateUnitKey;
import ru.rentoptima.util.PdAnonymizer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Заявки с виджета / страницы бронирования (блок 4.9), режим REQUEST.
 * <p>
 * Заявка — это пара: {@link CalendarBlock} типа HOLD (держит даты и сразу уходит
 * в iCal-экспорт остальных каналов) и {@link Booking} со статусом PENDING
 * (контакты гостя и сумма). Их связывает UUID заявки: {@code external_uid}
 * у блока и {@code external_id} у брони.
 * <ul>
 *   <li>подтверждение — бронь становится BOOKED, hold удаляется (занятость теперь даёт сама бронь);</li>
 *   <li>отклонение / истечение срока — hold удаляется, бронь получает DECLINED / EXPIRED.</li>
 * </ul>
 * Денег сервис не принимает и ничего не подтверждает сам — решение всегда за хостом.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WidgetBookingService {

    public static final String DATA_SOURCE = "WIDGET";
    public static final String SOURCE = "DIRECT_WIDGET";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_BOOKED = "BOOKED";
    public static final String STATUS_DECLINED = "DECLINED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private final BookingWidgetRepository widgetRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final PropertyRepository propertyRepo;
    private final TenantRepository tenantRepo;
    private final BookingRepository bookingRepo;
    private final CalendarBlockRepository blockRepo;
    private final AvailabilityService availability;
    private final EffectivePriceService effectivePrice;

    public Optional<BookingWidget> findActive(String secret) {
        if (secret == null || secret.isBlank() || secret.length() > 64) return Optional.empty();
        return widgetRepo.findBySecretAndActiveTrue(secret);
    }

    /** Последний день, на который можно назначить выезд. */
    public static LocalDate maxDate(BookingWidget w, LocalDate today) {
        return today.plusDays(w.getBookingWindowDays());
    }

    /** Ночи, в которые категория распродана, в пределах окна бронирования. */
    @Transactional(readOnly = true)
    public List<LocalDate> busyNights(BookingWidget w, LocalDate today) {
        UnitType ut = unitTypeRepo.findById(w.getUnitTypeId()).orElse(null);
        if (ut == null) return List.of();
        return availability.soldOutDates(ut, today, maxDate(w, today), null);
    }

    /**
     * Проверка дат без похода в БД.
     *
     * @return текст ошибки для гостя или null, если даты допустимы
     */
    public static String validateStay(LocalDate from, LocalDate to, LocalDate today,
                                      int minNights, int maxNights, int windowDays) {
        if (from == null || to == null) return "Выберите даты заезда и выезда";
        if (!to.isAfter(from)) return "Дата выезда должна быть позже даты заезда";
        if (from.isBefore(today)) return "Дата заезда уже прошла";
        if (to.isAfter(today.plusDays(windowDays))) {
            return "Бронирование открыто не дальше чем на " + windowDays + " дн. вперёд";
        }
        long nights = ChronoUnit.DAYS.between(from, to);
        if (nights < minNights) return "Минимальный срок — " + minNights + " ноч.";
        if (nights > maxNights) return "Максимальный срок — " + maxNights + " ноч.";
        return null;
    }

    /** Стоимость проживания по плановым ценам. total == null — цена на часть ночей не задана. */
    @Transactional(readOnly = true)
    public Quote quote(BookingWidget w, LocalDate from, LocalDate to) {
        Map<DateUnitKey, BigDecimal> prices = effectivePrice.resolveBatch(
                List.of(w.getUnitTypeId()), from, to.minusDays(1));
        List<NightPrice> breakdown = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            BigDecimal price = prices.get(new DateUnitKey(w.getUnitTypeId(), d));
            breakdown.add(new NightPrice(d, price));
            if (price == null) complete = false;
            else total = total.add(price);
        }
        return new Quote((int) ChronoUnit.DAYS.between(from, to), complete ? total : null, breakdown);
    }

    /**
     * Создаёт заявку: hold + бронь PENDING в одной транзакции.
     * Вызывающий код уже проверил капчу и частоту запросов.
     */
    @Transactional
    public Submission submit(BookingWidget w, StayRequest req) {
        LocalDate today = LocalDate.now();
        String error = validateStay(req.from(), req.to(), today,
                w.getMinNights(), w.getMaxNights(), w.getBookingWindowDays());
        if (error != null) return Submission.rejected(error);
        if (req.guests() < 1 || req.guests() > w.getMaxGuests()) {
            return Submission.rejected("Число гостей — от 1 до " + w.getMaxGuests());
        }
        String phone = normalizePhone(req.phone());
        if (phone == null) return Submission.rejected("Укажите телефон для связи");
        if (!req.consent()) {
            return Submission.rejected("Нужно согласие на обработку контактных данных");
        }

        // Блокировка строки категории: вторая одновременная заявка на те же даты
        // дождётся первой и увидит её hold.
        UnitType ut = unitTypeRepo.lockById(w.getUnitTypeId()).orElse(null);
        if (ut == null || !Boolean.TRUE.equals(ut.getActive())) {
            return Submission.rejected("Бронирование временно недоступно");
        }
        if (!availability.soldOutDates(ut, req.from(), req.to(), null).isEmpty()) {
            return Submission.rejected("Эти даты только что заняли. Выберите другие");
        }

        String requestId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusHours(w.getHoldHours());

        CalendarBlock hold = new CalendarBlock();
        hold.setTenantId(w.getTenantId());
        hold.setUnitTypeId(ut.getId());
        hold.setChannelId(w.getChannelId());
        hold.setExternalUid(requestId);
        hold.setBlockType(CalendarBlock.BlockType.HOLD);
        hold.setFromDate(req.from());
        hold.setToDate(req.to());
        hold.setReason("Заявка с виджета");
        hold.setExpiresAt(expiresAt);
        blockRepo.save(hold);

        Quote quote = quote(w, req.from(), req.to());

        Booking b = new Booking();
        b.setTenant(tenantRepo.getReferenceById(w.getTenantId()));
        b.setProperty(propertyRepo.getReferenceById(ut.getPropertyId()));
        b.setUnitTypeId(ut.getId());
        b.setChannelId(w.getChannelId());
        b.setExternalId(requestId);
        b.setStatus(STATUS_PENDING);
        b.setSource(SOURCE);
        b.setDataSource(DATA_SOURCE);
        b.setCheckIn(req.from());
        b.setCheckOut(req.to());
        b.setNights(quote.nights());
        if (quote.total() != null) b.setAmount(quote.total());
        b.setGuestName(PdAnonymizer.toInitial(req.name()));
        b.setGuestPhone(phone);
        b.setGuestCount(req.guests());
        b.setNotes(notesOf(req));
        b.setUpdatedAt(now);
        bookingRepo.save(b);

        log.info("Заявка с виджета {}: {} — {}, tenant={}", w.getId(), req.from(), req.to(), w.getTenantId());
        return Submission.accepted(b, expiresAt, quote);
    }

    @Transactional
    public boolean confirm(Long tenantId, Long bookingId) {
        Booking b = pendingOf(tenantId, bookingId);
        if (b == null) return false;
        b.setStatus(STATUS_BOOKED);
        b.setUpdatedAt(LocalDateTime.now());
        bookingRepo.save(b);
        // Даты теперь занимает сама бронь — hold больше не нужен
        releaseHold(b);
        return true;
    }

    @Transactional
    public boolean decline(Long tenantId, Long bookingId) {
        Booking b = pendingOf(tenantId, bookingId);
        if (b == null) return false;
        b.setStatus(STATUS_DECLINED);
        b.setUpdatedAt(LocalDateTime.now());
        bookingRepo.save(b);
        releaseHold(b);
        return true;
    }

    /** Снимает просроченные резервы: даты освобождаются, заявка помечается EXPIRED. */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    @Transactional
    public void expireHolds() {
        LocalDateTime now = LocalDateTime.now();
        for (CalendarBlock hold : blockRepo.findByExpiresAtBefore(now)) {
            if (hold.getChannelId() != null && hold.getExternalUid() != null) {
                bookingRepo.findByChannelIdAndExternalId(hold.getChannelId(), hold.getExternalUid())
                        .filter(b -> STATUS_PENDING.equals(b.getStatus()))
                        .ifPresent(b -> {
                            b.setStatus(STATUS_EXPIRED);
                            b.setUpdatedAt(now);
                            bookingRepo.save(b);
                        });
            }
            blockRepo.delete(hold);
            log.info("Hold {} истёк и снят (unit_type={})", hold.getId(), hold.getUnitTypeId());
        }
    }

    /** Когда истекает резерв по заявке; null, если резерва уже нет. */
    public LocalDateTime holdExpiresAt(Booking b) {
        if (b.getChannelId() == null || b.getExternalId() == null) return null;
        return blockRepo.findByChannelIdAndExternalUid(b.getChannelId(), b.getExternalId())
                .map(CalendarBlock::getExpiresAt).orElse(null);
    }

    private Booking pendingOf(Long tenantId, Long bookingId) {
        Booking b = bookingRepo.findById(bookingId).orElse(null);
        if (b == null || !tenantId.equals(b.getTenant().getId())) return null;
        if (!DATA_SOURCE.equals(b.getDataSource()) || !STATUS_PENDING.equals(b.getStatus())) return null;
        return b;
    }

    private void releaseHold(Booking b) {
        if (b.getChannelId() == null || b.getExternalId() == null) return;
        blockRepo.findByChannelIdAndExternalUid(b.getChannelId(), b.getExternalId())
                .ifPresent(blockRepo::delete);
    }

    /** Оставляет «+» и цифры; null, если цифр меньше, чем бывает в телефоне. */
    static String normalizePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < 10 || digits.length() > 15) return null;
        return (raw.trim().startsWith("+") ? "+" : "") + digits;
    }

    private static String notesOf(StayRequest req) {
        List<String> parts = new ArrayList<>();
        if (req.email() != null && !req.email().isBlank()) parts.add("Email: " + cut(req.email(), 120));
        if (req.note() != null && !req.note().isBlank()) parts.add("Комментарий: " + cut(req.note(), 1000));
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private static String cut(String s, int max) {
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    public record StayRequest(LocalDate from, LocalDate to, int guests,
                              String name, String phone, String email, String note,
                              boolean consent) {}

    public record NightPrice(LocalDate date, BigDecimal price) {}

    public record Quote(int nights, BigDecimal total, List<NightPrice> breakdown) {}

    /** booking == null — заявка отклонена, причина в error. */
    public record Submission(Booking booking, LocalDateTime holdExpiresAt, Quote quote, String error) {
        static Submission accepted(Booking booking, LocalDateTime holdExpiresAt, Quote quote) {
            return new Submission(booking, holdExpiresAt, quote, null);
        }

        static Submission rejected(String error) {
            return new Submission(null, null, null, error);
        }

        public boolean ok() {
            return booking != null;
        }
    }
}
