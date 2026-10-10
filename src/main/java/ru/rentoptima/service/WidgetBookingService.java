package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.PromoCode;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.PromoCodeRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.TenantRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.service.EffectivePriceService.DateUnitKey;
import ru.rentoptima.util.PdAnonymizer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Заявки и брони с виджета / страницы бронирования.
 * <p>
 * Заявка — это пара: {@link CalendarBlock} канала виджета («якорь») и {@link Booking}.
 * Их связывает UUID заявки: {@code external_uid} у блока и {@code external_id} у брони.
 * <ul>
 *   <li>режим REQUEST: блок HOLD с {@code expires_at} держит даты, бронь PENDING;
 *       подтверждение — бронь BOOKED, блок становится WIDGET_BOOKING;</li>
 *   <li>режим INSTANT: сразу бронь BOOKED и блок WIDGET_BOOKING;</li>
 *   <li>отклонение, истечение срока, отмена — бронь DECLINED / EXPIRED / CANCELLED,
 *       блок удаляется мягко ({@code cancelled_at}).</li>
 * </ul>
 * Якорь живёт столько же, сколько бронь, потому что на нём держится защита от эха —
 * та же, что у ручных записей (CLAUDE.md, «Ручные записи в многоканальной среде»):
 * в экспорт заявка идёт под UID-маркером {@code optirent-widget-<UUID>}, к якорю
 * привязываются эхо-связи и тени, отменённая заявка 90 дней отдаётся со
 * STATUS:CANCELLED, а её тени скрываются автоматически ({@link EchoShadowService}).
 * Занятость подтверждённой брони даёт сама бронь — блок WIDGET_BOOKING
 * AvailabilityService не считает.
 * <p>
 * Денег сервис не принимает.
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
    public static final String STATUS_CANCELLED = "CANCELLED";

    private static final int MAX_PETS = 5;
    private static final int ALTERNATIVES = 3;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}");
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingWidgetRepository widgetRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final PropertyRepository propertyRepo;
    private final TenantRepository tenantRepo;
    private final BookingRepository bookingRepo;
    private final CalendarBlockRepository blockRepo;
    private final PromoCodeRepository promoRepo;
    private final AvailabilityService availability;
    private final EffectivePriceService effectivePrice;
    private final EchoShadowService shadows;

    /** Виджет по секрету — для прежних ссылок /book/ и /widget/. */
    public Optional<BookingWidget> findActive(String secret) {
        if (secret == null || secret.isBlank() || secret.length() > 64) return Optional.empty();
        return widgetRepo.findBySecretAndActiveTrue(secret);
    }

    /** Виджет по публичному адресу — для API. */
    public Optional<BookingWidget> findBySlug(String slug) {
        if (!WidgetSlug.valid(slug)) return Optional.empty();
        return widgetRepo.findBySlugAndActiveTrue(slug);
    }

    /** Последний день, на который можно назначить выезд. */
    public static LocalDate maxDate(BookingWidget w, LocalDate today) {
        return today.plusDays(w.getBookingWindowDays());
    }

    public static WidgetCalendar.Rules rules(BookingWidget w, LocalDate today) {
        return new WidgetCalendar.Rules(today, maxDate(w, today), w.getMinNights(), w.getMaxNights(),
                WidgetCalendar.parseDays(w.getNoCheckinDays()),
                WidgetCalendar.parseDays(w.getNoCheckoutDays()));
    }

    /** Ночи, в которые категория распродана, в пределах окна бронирования. */
    @Transactional(readOnly = true)
    public List<LocalDate> busyNights(BookingWidget w, LocalDate today) {
        return new ArrayList<>(busySet(w, today, maxDate(w, today))).stream().sorted().toList();
    }

    private Set<LocalDate> busySet(BookingWidget w, LocalDate from, LocalDate to) {
        UnitType ut = unitTypeRepo.findById(w.getUnitTypeId()).orElse(null);
        if (ut == null) return Set.of();
        return new HashSet<>(availability.soldOutDates(ut, from, to, null));
    }

    /**
     * Дни {@code [from, to)} для календаря виджета: занятость, цена ночи, можно ли
     * заехать и выехать. Интервал обрезается окном бронирования.
     */
    @Transactional(readOnly = true)
    public List<WidgetCalendar.Day> days(BookingWidget w, LocalDate from, LocalDate to, LocalDate today) {
        WidgetCalendar.Rules rules = rules(w, today);
        LocalDate start = from == null || from.isBefore(today) ? today : from;
        LocalDate limit = rules.maxDate().plusDays(1);
        LocalDate end = to == null || to.isAfter(limit) ? limit : to;
        if (!end.isAfter(start)) return List.of();

        // Занятость нужна с запасом: вчерашняя ночь решает, можно ли выехать в первый
        // день, а ночи после конца — хватает ли места на минимальный срок.
        Set<LocalDate> busy = busySet(w, start.minusDays(1), end.plusDays(w.getMinNights()));
        Map<LocalDate, BigDecimal> prices = new HashMap<>();
        if (Boolean.TRUE.equals(w.getShowPrice())) {
            effectivePrice.resolveBatch(List.of(w.getUnitTypeId()), start, end.minusDays(1))
                    .forEach((key, price) -> prices.put(key.date(), price));
        }
        return WidgetCalendar.days(start, end, busy, prices, rules);
    }

    /** Ближайшие свободные даты той же длины — когда выбранные заняты. */
    @Transactional(readOnly = true)
    public List<WidgetCalendar.Stay> alternatives(BookingWidget w, LocalDate checkin, LocalDate checkout,
                                                  LocalDate today) {
        WidgetCalendar.Rules rules = rules(w, today);
        return WidgetCalendar.alternatives(checkin, checkout,
                busySet(w, today, rules.maxDate()), rules, ALTERNATIVES);
    }

    /**
     * Проверка дат без похода в БД.
     *
     * @return текст ошибки для гостя или null, если даты допустимы
     */
    public static String validateStay(LocalDate from, LocalDate to, LocalDate today,
                                      int minNights, int maxNights, int windowDays) {
        WidgetCalendar.Rules rules = new WidgetCalendar.Rules(today, today.plusDays(windowDays),
                minNights, maxNights, Set.of(), Set.of());
        WidgetError error = WidgetCalendar.checkRules(from, to, rules);
        return error == null ? null : message(error, minNights, maxNights, windowDays, 0);
    }

    /** Текст причины отказа с параметрами виджета. */
    public static String message(WidgetError error, BookingWidget w) {
        return message(error, w.getMinNights(), w.getMaxNights(), w.getBookingWindowDays(), w.getMaxGuests());
    }

    private static String message(WidgetError error, int minNights, int maxNights, int windowDays, int maxGuests) {
        return switch (error) {
            case MIN_NIGHTS -> error.message(minNights);
            case MAX_NIGHTS -> error.message(maxNights);
            case WINDOW -> error.message(windowDays);
            case GUESTS -> error.message(maxGuests);
            default -> error.message();
        };
    }

    /**
     * Стоимость проживания: плановые цены ночей, скидка за длительность, промокод, уборка.
     *
     * @param promo уже проверенный промокод ({@link #findPromo}) или null
     */
    @Transactional(readOnly = true)
    public WidgetPricing.Quote quote(BookingWidget w, LocalDate from, LocalDate to, PromoCode promo) {
        Map<DateUnitKey, BigDecimal> prices = effectivePrice.resolveBatch(
                List.of(w.getUnitTypeId()), from, to.minusDays(1));
        List<WidgetPricing.NightPrice> nights = new ArrayList<>();
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            nights.add(new WidgetPricing.NightPrice(d, prices.get(new DateUnitKey(w.getUnitTypeId(), d))));
        }
        return WidgetPricing.quote(nights, w.getCleaningFee(), w.getWeeklyDiscountPercent(),
                w.getMonthlyDiscountPercent(), w.getPrepaymentPercent(), promo);
    }

    /** Промокод виджета по введённому коду; пустой код — промокода нет, ошибки тоже нет. */
    @Transactional(readOnly = true)
    public PromoCheck findPromo(BookingWidget w, String rawCode, LocalDate today) {
        String code = PromoCode.normalize(rawCode);
        if (code == null) return PromoCheck.none();
        return checkPromo(promoRepo.findByWidgetIdAndCode(w.getId(), code).orElse(null), today);
    }

    static PromoCheck checkPromo(PromoCode promo, LocalDate today) {
        if (promo == null || !Boolean.TRUE.equals(promo.getActive())) {
            return new PromoCheck(null, WidgetError.PROMO_INVALID);
        }
        if (promo.getValidFrom() != null && today.isBefore(promo.getValidFrom())) {
            return new PromoCheck(null, WidgetError.PROMO_INVALID);
        }
        if (promo.getValidUntil() != null && today.isAfter(promo.getValidUntil())) {
            return new PromoCheck(null, WidgetError.PROMO_EXPIRED);
        }
        if (promo.getMaxUses() != null && promo.getUsedCount() >= promo.getMaxUses()) {
            return new PromoCheck(null, WidgetError.PROMO_EXHAUSTED);
        }
        return new PromoCheck(promo, null);
    }

    /**
     * Создаёт заявку или бронь в одной транзакции. Вызывающий код уже проверил
     * honeypot, частоту запросов и время заполнения формы.
     */
    @Transactional
    public Submission submit(BookingWidget w, StayRequest req) {
        LocalDate today = LocalDate.now();
        WidgetCalendar.Rules rules = rules(w, today);
        WidgetError error = WidgetCalendar.checkRules(req.from(), req.to(), rules);
        if (error != null) return reject(w, error);
        if (req.adults() < 1 || req.children() < 0 || req.adults() + req.children() > w.getMaxGuests()) {
            return reject(w, WidgetError.GUESTS);
        }
        if (req.pets() < 0 || req.pets() > MAX_PETS
                || (req.pets() > 0 && !Boolean.TRUE.equals(w.getPetsAllowed()))) {
            return reject(w, WidgetError.PETS_NOT_ALLOWED);
        }
        String phone = normalizePhone(req.phone());
        if (phone == null) return reject(w, WidgetError.PHONE);
        String email = req.email() == null || req.email().isBlank() ? null : req.email().trim();
        if (email != null && (email.length() > 255 || !EMAIL.matcher(email).matches())) {
            return reject(w, WidgetError.EMAIL);
        }
        if (!req.consent()) return reject(w, WidgetError.CONSENT);

        // Блокировка строки категории: вторая одновременная заявка на те же даты
        // дождётся первой и увидит её резерв или бронь.
        UnitType ut = unitTypeRepo.lockById(w.getUnitTypeId()).orElse(null);
        if (ut == null || !Boolean.TRUE.equals(ut.getActive())) return reject(w, WidgetError.UNAVAILABLE);
        if (!availability.soldOutDates(ut, req.from(), req.to(), null).isEmpty()) {
            return reject(w, WidgetError.DATES_TAKEN);
        }

        PromoCode promo = null;
        String promoCode = PromoCode.normalize(req.promoCode());
        if (promoCode != null) {
            // Строка промокода тоже блокируется: последнее применение достанется одному
            PromoCheck check = checkPromo(
                    promoRepo.lockByWidgetIdAndCode(w.getId(), promoCode).orElse(null), today);
            if (check.error() != null) return reject(w, check.error());
            promo = check.promo();
        }

        WidgetPricing.Quote quote = quote(w, req.from(), req.to(), promo);
        if (req.expectedTotal() != null && quote.complete()
                && req.expectedTotal().compareTo(quote.total()) != 0) {
            // Гость видел другую сумму: цену или скидку поменяли, пока он заполнял форму
            return new Submission(null, null, quote, WidgetError.PRICE_CHANGED,
                    WidgetError.PRICE_CHANGED.message());
        }

        boolean instant = BookingWidget.MODE_INSTANT.equals(w.getMode());
        String requestId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = instant ? null : now.plusMinutes(w.getHoldMinutes());

        CalendarBlock anchor = new CalendarBlock();
        anchor.setTenantId(w.getTenantId());
        anchor.setUnitTypeId(ut.getId());
        anchor.setChannelId(w.getChannelId());
        anchor.setExternalUid(requestId);
        anchor.setBlockType(instant ? CalendarBlock.BlockType.WIDGET_BOOKING : CalendarBlock.BlockType.HOLD);
        anchor.setFromDate(req.from());
        anchor.setToDate(req.to());
        anchor.setReason(instant ? "Бронь с виджета" : "Заявка с виджета");
        anchor.setExpiresAt(expiresAt);
        blockRepo.save(anchor);

        Booking b = new Booking();
        b.setTenant(tenantRepo.getReferenceById(w.getTenantId()));
        b.setProperty(propertyRepo.getReferenceById(ut.getPropertyId()));
        b.setUnitTypeId(ut.getId());
        b.setChannelId(w.getChannelId());
        b.setExternalId(requestId);
        b.setStatus(instant ? STATUS_BOOKED : STATUS_PENDING);
        b.setSource(SOURCE);
        b.setDataSource(DATA_SOURCE);
        b.setCheckIn(req.from());
        b.setCheckOut(req.to());
        b.setNights(quote.nights());
        if (quote.complete()) {
            b.setAmount(quote.total());
            b.setCleaningFee(quote.cleaningFee());
            b.setDiscountAmount(quote.discountTotal());
            b.setPrepaymentAmount(quote.prepayment());
        }
        b.setGuestName(PdAnonymizer.toInitial(req.name()));
        b.setGuestPhone(phone);
        b.setGuestEmail(email);
        b.setGuestCount(req.adults() + req.children());
        b.setAdults(req.adults());
        b.setChildren(req.children());
        b.setPets(req.pets());
        b.setLocale("en".equals(req.locale()) ? "en" : "ru");
        b.setPublicCode(newPublicCode());
        b.setNotes(notesOf(req));
        b.setUtmSource(cutOrNull(req.utmSource(), 100));
        b.setUtmMedium(cutOrNull(req.utmMedium(), 100));
        b.setUtmCampaign(cutOrNull(req.utmCampaign(), 100));
        b.setReferrer(cutOrNull(req.referrer(), 500));
        b.setConsentAt(now);
        b.setUpdatedAt(now);
        if (promo != null) {
            b.setPromoCodeId(promo.getId());
            promo.setUsedCount(promo.getUsedCount() + 1);
            promoRepo.save(promo);
        }
        bookingRepo.save(b);

        log.info("{} с виджета {}: {} — {}, tenant={}", instant ? "Бронь" : "Заявка",
                w.getId(), req.from(), req.to(), w.getTenantId());
        return new Submission(b, expiresAt, quote, null, null);
    }

    private static Submission reject(BookingWidget w, WidgetError error) {
        return new Submission(null, null, null, error, message(error, w));
    }

    /** Хозяин подтвердил заявку: бронь BOOKED, резерв становится якорем брони. */
    @Transactional
    public Outcome confirm(Long tenantId, Long bookingId) {
        Booking b = widgetBooking(tenantId, bookingId, STATUS_PENDING);
        if (b == null) return Outcome.notFound();
        LocalDateTime now = LocalDateTime.now();
        b.setStatus(STATUS_BOOKED);
        b.setUpdatedAt(now);
        bookingRepo.save(b);
        anchorOf(b).ifPresent(anchor -> {
            // Даты теперь занимает сама бронь; блок остаётся ради эхо-связей и теней
            anchor.setBlockType(CalendarBlock.BlockType.WIDGET_BOOKING);
            anchor.setExpiresAt(null);
            anchor.setReason("Бронь с виджета");
            anchor.setUpdatedAt(now);
            blockRepo.save(anchor);
        });
        return new Outcome(b, List.of());
    }

    /** Хозяин отклонил заявку: даты свободны. */
    @Transactional
    public Outcome decline(Long tenantId, Long bookingId) {
        Booking b = widgetBooking(tenantId, bookingId, STATUS_PENDING);
        if (b == null) return Outcome.notFound();
        return new Outcome(b, close(b, STATUS_DECLINED));
    }

    /** Хозяин отменил уже подтверждённую бронь с виджета. */
    @Transactional
    public Outcome cancel(Long tenantId, Long bookingId) {
        Booking b = widgetBooking(tenantId, bookingId, STATUS_BOOKED);
        if (b == null) return Outcome.notFound();
        return new Outcome(b, close(b, STATUS_CANCELLED));
    }

    /** Снимает просроченные резервы: даты освобождаются, заявка помечается EXPIRED. */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    @Transactional
    public void expireHolds() {
        LocalDateTime now = LocalDateTime.now();
        for (CalendarBlock hold : blockRepo.findByExpiresAtBefore(now)) {
            if (!hold.isWidgetOwned()) {
                blockRepo.delete(hold);
                continue;
            }
            Booking b = hold.getExternalUid() == null ? null
                    : bookingRepo.findByChannelIdAndExternalId(hold.getChannelId(), hold.getExternalUid())
                            .filter(x -> STATUS_PENDING.equals(x.getStatus())).orElse(null);
            if (b != null) {
                close(b, STATUS_EXPIRED);
            } else {
                cancelAnchor(hold, now);
            }
            log.info("Hold {} истёк и снят (unit_type={})", hold.getId(), hold.getUnitTypeId());
        }
    }

    /**
     * Закрывает заявку или бронь: статус, возврат применения промокода, мягкое удаление
     * якоря со скрытием его теней.
     *
     * @return тени, оставленные закрывать даты (похожи на настоящую бронь с площадки)
     */
    private List<String> close(Booking b, String status) {
        LocalDateTime now = LocalDateTime.now();
        b.setStatus(status);
        b.setUpdatedAt(now);
        bookingRepo.save(b);
        if (b.getPromoCodeId() != null) {
            promoRepo.lockById(b.getPromoCodeId()).ifPresent(promo -> {
                promo.setUsedCount(Math.max(0, promo.getUsedCount() - 1));
                promoRepo.save(promo);
            });
        }
        return anchorOf(b).map(anchor -> cancelAnchor(anchor, now)).orElse(List.of());
    }

    /**
     * Мягкое удаление якоря — как у ручной записи: занятостью он больше не считается,
     * 90 дней уходит в экспорт со STATUS:CANCELLED и держит эхо-связи, чтобы копия у
     * площадки не вернулась блокировкой; тени без признаков настоящей брони скрываются.
     */
    private List<String> cancelAnchor(CalendarBlock anchor, LocalDateTime now) {
        if (anchor.getCancelledAt() != null) return List.of();
        List<String> kept = shadows.release(anchor.getTenantId(), anchor.getId());
        anchor.setCancelledAt(now);
        anchor.setExpiresAt(null);
        anchor.setUpdatedAt(now);
        blockRepo.save(anchor);
        return kept;
    }

    private Optional<CalendarBlock> anchorOf(Booking b) {
        if (b.getChannelId() == null || b.getExternalId() == null) return Optional.empty();
        return blockRepo.findByChannelIdAndExternalUid(b.getChannelId(), b.getExternalId());
    }

    /** Когда истекает резерв по заявке; null, если резерва уже нет. */
    public LocalDateTime holdExpiresAt(Booking b) {
        return anchorOf(b).filter(a -> a.getCancelledAt() == null)
                .map(CalendarBlock::getExpiresAt).orElse(null);
    }

    /** Бронь по UUID заявки — для экрана подтверждения и файла календаря гостя. */
    @Transactional(readOnly = true)
    public Optional<Booking> findByRequestId(BookingWidget w, String requestId) {
        if (requestId == null || requestId.length() != 36) return Optional.empty();
        return bookingRepo.findByChannelIdAndExternalId(w.getChannelId(), requestId)
                .filter(b -> DATA_SOURCE.equals(b.getDataSource()));
    }

    private Booking widgetBooking(Long tenantId, Long bookingId, String status) {
        Booking b = bookingRepo.findById(bookingId).orElse(null);
        if (b == null || !tenantId.equals(b.getTenant().getId())) return null;
        if (!DATA_SOURCE.equals(b.getDataSource()) || !status.equals(b.getStatus())) return null;
        return b;
    }

    /** Оставляет «+» и цифры; null, если цифр меньше, чем бывает в телефоне. */
    static String normalizePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < 10 || digits.length() > 15) return null;
        return (raw.trim().startsWith("+") ? "+" : "") + digits;
    }

    static String newPublicCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return sb.toString();
    }

    private static String notesOf(StayRequest req) {
        List<String> parts = new ArrayList<>();
        if (req.children() > 0) parts.add("Детей: " + req.children());
        if (req.pets() > 0) parts.add("Питомцев: " + req.pets());
        if (req.note() != null && !req.note().isBlank()) parts.add("Комментарий: " + cut(req.note(), 1000));
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private static String cut(String s, int max) {
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String cutOrNull(String s, int max) {
        if (s == null || s.isBlank()) return null;
        return cut(s, max);
    }

    /**
     * @param expectedTotal сумма, которую видел гость; null — не сверять
     */
    public record StayRequest(LocalDate from, LocalDate to, int adults, int children, int pets,
                              String name, String phone, String email, String note,
                              boolean consent, String promoCode, BigDecimal expectedTotal,
                              String locale, String utmSource, String utmMedium, String utmCampaign,
                              String referrer) {
    }

    /** promo == null и error == null — промокод не вводили. */
    public record PromoCheck(PromoCode promo, WidgetError error) {
        static PromoCheck none() {
            return new PromoCheck(null, null);
        }
    }

    /**
     * booking == null — отказ, причина в error. Для PRICE_CHANGED в quote лежит
     * актуальный расчёт.
     */
    public record Submission(Booking booking, LocalDateTime holdExpiresAt, WidgetPricing.Quote quote,
                             WidgetError error, String errorMessage) {
        public boolean ok() {
            return booking != null;
        }
    }

    /**
     * Итог действия хозяина над заявкой.
     *
     * @param booking     null — заявка не найдена или уже обработана
     * @param keptShadows тени, оставленные закрывать даты, — для предупреждения хозяину
     */
    public record Outcome(Booking booking, List<String> keptShadows) {
        static Outcome notFound() {
            return new Outcome(null, List.of());
        }

        public boolean ok() {
            return booking != null;
        }
    }
}
