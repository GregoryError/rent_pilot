package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.Property;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.PropertyRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PricingEngine {

    private final PropertyRepository propertyRepo;
    private final BookingRepository bookingRepo;
    private final SettingsService settings;
    private final ProductionCalendarService prodCalendar;
    private final RealtyCalendarClient rcClient;
    private final BookingStatsService statsService;
    private final AiPricingAdvisor aiAdvisor;
    private final RcSyncService rcSyncService;

    /**
     * Автопилот запускается каждый час.
     */
//    @Scheduled(fixedDelay = 3600000)
//    @Scheduled(fixedDelay = (60*1000)*3)
    public void runAutopilot() {

        List<Property> properties = propertyRepo.findAll().stream()
                .filter(p -> p.getActive()
                        && p.getRcObjectId() != null
                        && !p.getRcObjectId().isBlank())
                .toList();

        for (Property property : properties) {
            try {
                Long tenantId = property.getTenant().getId();

                String mode = settings.getValue(
                        tenantId,
                        "autopilot_mode"
                );

                if (mode == null || "OFF".equals(mode)) {
                    continue;
                }

                runForProperty(property, mode);

            } catch (Exception e) {
                log.error(
                        "Autopilot error for property {}: {}",
                        property.getName(),
                        e.getMessage(),
                        e
                );
            }
        }
    }

    /**
     * Расчёт рекомендаций для страницы админки.
     *
     * ВАЖНО:
     * tenantId здесь пока используется только для контекста.
     * Поиск Property желательно позже сделать
     * findByIdAndTenantId().
     */
    public List<PricingRecommendation> getRecommendations(
            Long tenantId,
            Long propertyId
    ) {
        Property property = propertyRepo
                .findById(propertyId)
                .orElseThrow();

        return calculateRecommendations(
                property,
                LocalDate.now(),
                LocalDate.now().plusDays(60)
        );
    }

    /**
     * Основной цикл автопилота для одного объекта.
     *
     * Перед изменением цен:
     *
     * 1. Получаем актуальное состояние календаря RC.
     * 2. Определяем закрытые вручную даты.
     * 3. Рассчитываем наши рекомендации.
     * 4. Исключаем:
     *      - локальные брони;
     *      - закрытые в RC даты;
     *      - большие изменения в SOFT.
     * 5. Отправляем только разрешённые даты.
     */
    public void runForProperty(
            Property property,
            String mode
    ) {

        Long tenantId = property.getTenant().getId();

        int openAheadDays = settings.getIntValue(
                tenantId,
                "open_ahead_days",
                30
        );

        int autoDelta = settings.getIntValue(
                tenantId,
                "auto_price_delta",
                50
        );

        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(openAheadDays);

        /*
         * ---------------------------------------------------------
         * 1. Получаем состояние календаря RC
         * ---------------------------------------------------------
         */

        CalendarState calendarState;

        try {
            calendarState = loadCalendarState(
                    property.getRcObjectId(),
                    from,
                    to
            );
        } catch (Exception e) {

            /*
             * Безопасное поведение:
             *
             * если не удалось получить состояние RC,
             * НЕ меняем цены.
             *
             * Иначе при проблеме API мы можем случайно
             * изменить вручную закрытые даты.
             */
            log.error(
                    "Autopilot [{}]: cannot read RC calendar for {}. " +
                            "NO prices will be changed: {}",
                    mode,
                    property.getName(),
                    e.getMessage(),
                    e
            );

            return;
        }

        /*
         * ---------------------------------------------------------
         * 2. Рассчитываем наши рекомендации
         * ---------------------------------------------------------
         */

        List<PricingRecommendation> recs =
                calculateRecommendations(
                        property,
                        from,
                        to
                );

        // Sync bookings from RC event_calendars
        try {
            syncRcBookings(property, from, to);
        } catch (Exception e) {
            log.warn("RC bookings sync failed for {}: {}", property.getName(), e.getMessage());
        }

        // AI adjustments (fallback to algorithm if unavailable)
        Map<LocalDate, Double> aiAdjustments = Map.of();
        try {
            aiAdjustments = aiAdvisor.getAdjustments(property, recs);
        } catch (Exception e) {
            log.warn("AI pricing skipped: {}", e.getMessage());
        }
        final Map<LocalDate, Double> adjustments = aiAdjustments;

        List<RealtyCalendarClient.SpecialPrice> items =
                new ArrayList<>();

        /*
         * ---------------------------------------------------------
         * 3. Фильтруем даты
         * ---------------------------------------------------------
         */

        for (PricingRecommendation rec : recs) {

            /*
             * День уже забронирован по данным нашей БД.
             */
            if (rec.status() == DayStatus.BOOKED) {

                log.debug(
                        "Autopilot: skip BOOKED date {} for {}",
                        rec.date(),
                        property.getName()
                );

                continue;
            }

            /*
             * День закрыт в RealtyCalendar.
             *
             * Это может быть ручное закрытие,
             * спецусловие или другое ограничение.
             */
            if (calendarState.closedDates().contains(rec.date())) {

                log.info(
                        "Autopilot: skip CLOSED date {} for {}",
                        rec.date(),
                        property.getName()
                );

                continue;
            }

            /*
             * SOFT:
             * не меняем цену, если изменение слишком большое.
             */
            if ("SOFT".equals(mode)
                    && Math.abs(rec.priceDelta()) > autoDelta) {

                log.debug(
                        "SOFT: skip large change for {} " +
                                "(delta={}₽)",
                        rec.date(),
                        rec.priceDelta()
                );

                continue;
            }

            /*
             * День разрешён:
             * добавляем его в POST.
             */
            items.add(
                    new RealtyCalendarClient.SpecialPrice(
                            rec.date(),
                            applyAiAdjustment(rec, adjustments, tenantId),
                            rec.recommendedMinStay()
                    )
            );
        }

        /*
         * ---------------------------------------------------------
         * 4. Нечего отправлять
         * ---------------------------------------------------------
         */

        if (items.isEmpty()) {

            log.info(
                    "Autopilot [{}]: no items to push for {}",
                    mode,
                    property.getName()
            );

            return;
        }

        /*
         * ---------------------------------------------------------
         * 5. Отправляем цены
         * ---------------------------------------------------------
         */

        log.info(
                "Autopilot [{}]: pushing {} price updates for {}",
                mode,
                items.size(),
                property.getName()
        );

        rcClient.saveSpecialPrices(
                property.getRcObjectId(),
                items
        );
    }

    /**
     * Получает календарь RC и извлекает закрытые даты.
     *
     * ВАЖНО:
     * Здесь мы НЕ создаём Booking.
     *
     * special_prices — это состояние спецусловий календаря,
     * а не надёжный источник бронирований.
     */
    private CalendarState loadCalendarState(
            String rcObjectId,
            LocalDate from,
            LocalDate to
    ) {

        JsonNode response = rcClient.getSpecialPrices(
                rcObjectId,
                from,
                to
        );

        if (response == null || response.isMissingNode()) {
            throw new IllegalStateException(
                    "RealtyCalendar returned empty calendar response"
            );
        }

        JsonNode items = response.path("items");

        if (!items.isArray()) {
            throw new IllegalStateException(
                    "RealtyCalendar calendar response does not contain " +
                            "an 'items' array"
            );
        }

        Set<LocalDate> closedDates = new HashSet<>();

        for (JsonNode item : items) {

            String dateText = item.path("date").asText(null);

            if (dateText == null || dateText.isBlank()) {
                log.warn(
                        "RC calendar item without date: {}",
                        item
                );
                continue;
            }

            LocalDate date;

            try {
                date = LocalDate.parse(dateText);
            } catch (Exception e) {
                log.warn(
                        "Cannot parse RC calendar date '{}'",
                        dateText
                );
                continue;
            }

            /*
             * RC формат:
             *
             * "closed": {
             *     "actual": {
             *         "value": true
             *     }
             * }
             */
            boolean closed = item
                    .path("closed")
                    .path("actual")
                    .path("value")
                    .asBoolean(false);

            /*
             * Также учитываем запрет заезда/выезда.
             *
             * Для автопилота безопаснее считать такую дату
             * ограниченной и не менять её автоматически.
             */
            boolean closedOnArrival = item
                    .path("closed_on_arrivial")
                    .path("actual")
                    .path("value")
                    .asBoolean(false);

            boolean closedOnDeparture = item
                    .path("closed_on_departure")
                    .path("actual")
                    .path("value")
                    .asBoolean(false);

            if (closed || closedOnArrival || closedOnDeparture) {

                closedDates.add(date);

                log.debug(
                        "RC calendar: date {} is restricted " +
                                "(closed={}, arrival={}, departure={})",
                        date,
                        closed,
                        closedOnArrival,
                        closedOnDeparture
                );
            }
        }

        log.info(
                "RC calendar loaded: {} closed/restricted dates " +
                        "between {} and {}",
                closedDates.size(),
                from,
                to
        );

        return new CalendarState(closedDates);
    }

    /**
     * Основной расчёт рекомендаций.
     */
    public List<PricingRecommendation> calculateRecommendations(
            Property property,
            LocalDate from,
            LocalDate to
    ) {

        Long tenantId = property.getTenant().getId();

        Map<String, String> s =
                settings.getSettingsMap(tenantId);

        int weekdayBase = parseInt(
                s,
                "weekday_base_price",
                3200
        );

        int weekendBase = parseInt(
                s,
                "weekend_base_price",
                4200
        );

        int floorPrice = parseInt(
                s,
                "min_price_floor",
                2500
        );

        int ceilPrice = parseInt(
                s,
                "max_price_ceiling",
                6000
        );

        int maxMinStay = parseInt(
                s,
                "max_min_stay",
                10
        );

        int cleaningCost = parseInt(
                s,
                "cleaning_cost",
                1400
        );

        double markupPct = Double.parseDouble(
                s.getOrDefault(
                        "platform_markup_pct",
                        "18"
                )
        ) / 100.0;

        /*
         * Получаем активные бронирования из нашей БД.
         */
        List<Booking> bookings =
                bookingRepo.findActiveInRange(
                        tenantId,
                        property.getId(),
                        from,
                        to
                );

        /*
         * Строим множество забронированных дат для быстрого поиска.
         */
        Set<LocalDate> bookedDates = new HashSet<>();
        for (Booking b : bookings) {
            for (LocalDate d = b.getCheckIn(); d.isBefore(b.getCheckOut()); d = d.plusDays(1)) {
                if (!d.isBefore(from) && !d.isAfter(to)) bookedDates.add(d);
            }
        }

        /*
         * Находим свободные окна между бронями.
         * Ключевая идея: цена и min_stay задаются с учётом длины окна.
         */
        List<int[]> windows = new ArrayList<>(); // pairs [startEpoch, endEpoch] inclusive
        LocalDate winStart = null;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!bookedDates.contains(d)) {
                if (winStart == null) winStart = d;
            } else if (winStart != null) {
                windows.add(new int[]{(int) winStart.toEpochDay(), (int) d.minusDays(1).toEpochDay()});
                winStart = null;
            }
        }
        if (winStart != null) {
            windows.add(new int[]{(int) winStart.toEpochDay(), (int) to.toEpochDay()});
        }

        /*
         * Получаем gap-информацию.
         */
        List<BookingStatsService.GapInfo> gaps =
                statsService.detectGaps(
                        tenantId,
                        from,
                        to
                );

        Set<LocalDate> gapDates = new HashSet<>();

        for (var gap : gaps) {

            for (
                    LocalDate d = gap.from();
                    !d.isAfter(gap.to().minusDays(1));
                    d = d.plusDays(1)
            ) {
                gapDates.add(d);
            }
        }

        List<PricingRecommendation> result =
                new ArrayList<>();

        LocalDate today = LocalDate.now();

        for (
                LocalDate date = from;
                !date.isAfter(to);
                date = date.plusDays(1)
        ) {

            final LocalDate d = date;

            long daysAhead =
                    ChronoUnit.DAYS.between(
                            today,
                            d
                    );

            /*
             * Проверка локальной брони.
             */
            if (bookedDates.contains(d)) {

                result.add(
                        new PricingRecommendation(
                                d,
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                0,
                                0,
                                BigDecimal.ZERO,
                                DayStatus.BOOKED,
                                "Забронировано",
                                100
                        )
                );

                continue;
            }

            /*
             * Находим окно, к которому принадлежит эта дата.
             */
            int dayEpoch = (int) d.toEpochDay();
            int windowLen = 1;
            for (int[] w : windows) {
                if (dayEpoch >= w[0] && dayEpoch <= w[1]) {
                    windowLen = w[1] - w[0] + 1;
                    break;
                }
            }

            boolean isWeekend =
                    d.getDayOfWeek().getValue() >= 5;

            boolean isHoliday =
                    prodCalendar.isHoliday(d);

            boolean isGap =
                    gapDates.contains(d) || windowLen <= 2;

            int basePrice =
                    (isWeekend || isHoliday)
                            ? weekendBase
                            : weekdayBase;

            /*
             * Плавная ступенчатая лестница по расстоянию до даты.
             * min_stay ограничивается длиной окна.
             */
            double multiplier;
            int minStay;
            String reason;
            int confidence;

            if (daysAhead > 60) {
                minStay = Math.min(windowLen, maxMinStay);
                multiplier = 1.05;
                reason = "Далеко (" + daysAhead + "д) — окно " + windowLen + "н";
                confidence = 50;
            } else if (daysAhead > 45) {
                minStay = Math.min(windowLen, Math.max(7, maxMinStay - 1));
                multiplier = 1.02;
                reason = "45-60 дней, окно " + windowLen + "н";
                confidence = 55;
            } else if (daysAhead > 30) {
                minStay = Math.min(windowLen, Math.max(5, windowLen * 2 / 3));
                multiplier = 1.0;
                reason = "30-45 дней";
                confidence = 62;
            } else if (daysAhead > 21) {
                minStay = Math.min(windowLen, Math.max(4, windowLen / 2));
                multiplier = 1.0;
                reason = "21-30 дней";
                confidence = 67;
            } else if (daysAhead > 14) {
                minStay = Math.min(windowLen, Math.max(3, windowLen / 3));
                multiplier = 0.98;
                reason = "14-21 день";
                confidence = 72;
            } else if (daysAhead > 7) {
                minStay = windowLen >= 4 ? 3 : (windowLen >= 2 ? 2 : 1);
                multiplier = 0.95;
                reason = "7-14 дней — снижаем условия";
                confidence = 77;
            } else if (daysAhead > 3) {
                minStay = windowLen >= 2 ? 2 : 1;
                multiplier = 0.90;
                reason = "3-7 дней — 2 ночи";
                confidence = 83;
            } else {
                minStay = 1;
                multiplier = 0.85;
                reason = "< 3 дней — последний шанс";
                confidence = 90;
            }

            /*
             * Gap: короткое окно между бронями.
             */
            if (isGap) {

                multiplier *= 0.88;
                minStay = 1;

                reason = "Gap (окно " + windowLen + "н) — скидка";

                confidence = 88;
            }

            /*
             * Праздник.
             */
            if (isHoliday) {

                multiplier *= 1.12;

                reason += " + праздник";
            }

            /*
             * Длинное окно — можем позволить премию.
             */
            if (windowLen >= 7 && daysAhead > 14 && !isGap) {
                multiplier *= 1.05;
                reason += " (длинное окно)";
            }

            /*
             * min_stay не может превышать длину окна.
             */
            minStay = Math.max(1, Math.min(minStay, windowLen));

            /*
             * Цена RC.
             */
            int rcPrice =
                    (int) Math.round(
                            basePrice * multiplier
                    );

            rcPrice =
                    Math.max(
                            floorPrice,
                            Math.min(
                                    ceilPrice,
                                    rcPrice
                            )
                    );

            /*
             * Чистый доход за ночь.
             */
            BigDecimal netPerNight =
                    BigDecimal
                            .valueOf(rcPrice)
                            .subtract(
                                    BigDecimal.valueOf(
                                            cleaningCost
                                    )
                            );

            int delta =
                    rcPrice - basePrice;

            result.add(
                    new PricingRecommendation(
                            d,
                            BigDecimal.valueOf(basePrice),
                            BigDecimal.valueOf(rcPrice),
                            delta,
                            minStay,
                            netPerNight,
                            isGap
                                    ? DayStatus.GAP
                                    : DayStatus.FREE,
                            reason,
                            confidence
                    )
            );
        }

        return result;
    }

    private int parseInt(
            Map<String, String> map,
            String key,
            int def
    ) {

        try {

            return Integer.parseInt(
                    map.getOrDefault(
                            key,
                            String.valueOf(def)
                    )
            );

        } catch (NumberFormatException e) {

            return def;
        }
    }

    /**
     * Состояние дня.
     */
    public enum DayStatus {

        FREE,
        BOOKED,
        GAP
    }

    /**
     * Публичный триггер синхронизации из RC для одного объекта.
     * Используется при переходе на страницу календаря.
     * Возвращает map: дата -> цена из RC special_prices (для отображения в календаре).
     */
    public Map<LocalDate, Integer> triggerRcSyncWithPrices(Property property, LocalDate from, LocalDate to) {
        Map<LocalDate, Integer> rcPrices = new HashMap<>();
        try {
            JsonNode response = rcClient.getEventCalendars(property.getRcObjectId(), from, to);
            if (response == null || !response.has("items") || !response.get("items").isArray()
                    || response.get("items").isEmpty()) {
                return rcPrices;
            }

            JsonNode apt = response.get("items").get(0);

            // Sync bookings (same as before, refactored to accept parsed apt node)
            syncRcBookingsFromNode(property, apt);

            // Parse special_prices for per-date prices
            JsonNode specialPrices = apt.path("special_prices");
            if (specialPrices.isArray()) {
                for (JsonNode sp : specialPrices) {
                    if (sp.path("is_delete").asBoolean(false)) continue;
                    double price = sp.path("price").asDouble(0);
                    if (price <= 0) continue;

                    String beginStr = sp.path("begin_date").asText(null);
                    String endStr = sp.path("end_date").asText(null);
                    if (beginStr == null || endStr == null) continue;

                    try {
                        LocalDate start = LocalDate.parse(beginStr);
                        LocalDate end = LocalDate.parse(endStr);
                        int priceInt = (int) Math.round(price);
                        for (LocalDate d = start; d.isBefore(end); d = d.plusDays(1)) {
                            rcPrices.put(d, priceInt);
                        }
                    } catch (Exception e) {
                        log.warn("Cannot parse special_price date range: {}", e.getMessage());
                    }
                }
            }
            log.info("RC prices for {}: {} dates with special prices", property.getName(), rcPrices.size());
        } catch (Exception e) {
            log.warn("triggerRcSyncWithPrices failed for {}: {}", property.getName(), e.getMessage());
        }
        return rcPrices;
    }

    /**
     * Публичный триггер синхронизации из RC для одного объекта.
     * Используется при переходе на страницу календаря.
     */
    public void triggerRcSync(Property property, LocalDate from, LocalDate to) {
        try {
            syncRcBookings(property, from, to);
        } catch (Exception e) {
            log.warn("Manual RC sync failed for {}: {}", property.getName(), e.getMessage());
        }
    }

    /** Refactored: sync bookings from parsed apt node (reused by both entry points) */
    private void syncRcBookingsFromNode(Property property, JsonNode apt) {
        JsonNode events = apt.path("events");
        if (!events.isArray()) return;

        log.info("RC event_calendars: {} raw events for {}", events.size(), property.getName());

        List<RcBooking> rcBookings = new ArrayList<>();
        int skippedDeleted = 0;

        for (JsonNode ev : events) {
            if (ev.path("is_delete").asBoolean(false)) { skippedDeleted++; continue; }
            if (!"booked".equals(ev.path("status").asText(""))) continue;

            String beginStr = ev.path("begin_date").asText(null);
            String endStr = ev.path("end_date").asText(null);
            if (beginStr == null || endStr == null) continue;

            try {
                LocalDate start = LocalDate.parse(beginStr);
                LocalDate end = LocalDate.parse(endStr);
                long rcId = ev.path("id").asLong(0);
                String guest = ev.path("client").path("fio").asText(null);
                String phone = ev.path("client").path("phone").asText(null);
                double amount = ev.path("amount").asDouble(0);
                int sourceId = ev.path("source_id").asInt(0);

                if ((guest == null || guest.isBlank()) && amount == 0.0) {
                    guest = "Ручное закрытие RC";
                }

                rcBookings.add(new RcBooking(rcId, start, end, guest, phone, amount, sourceId));
            } catch (Exception e) {
                log.warn("Cannot parse RC event: {}", e.getMessage());
            }
        }

        log.info("RC sync {}: {} bookings (skipped {} deleted)",
                property.getName(), rcBookings.size(), skippedDeleted);

        if (!rcBookings.isEmpty()) {
            rcSyncService.syncBookings(property, rcBookings);
        }
    }

    /**
     * Загружает бронирования из RC event_calendars и синхронизирует их с БД.
     * Фильтрует технические блокировки (amount=0 + client=null).
     */
    private void syncRcBookings(Property property, LocalDate from, LocalDate to) {
        JsonNode response = rcClient.getEventCalendars(property.getRcObjectId(), from, to);
        if (response == null || !response.has("items") || !response.get("items").isArray()
                || response.get("items").isEmpty()) {
            log.info("RC event_calendars empty for {} in {}-{}", property.getName(), from, to);
            return;
        }

        JsonNode apt = response.get("items").get(0);
        JsonNode events = apt.path("events");
        if (!events.isArray()) return;

        log.info("RC event_calendars returned {} raw events for {}", events.size(), property.getName());

        List<RcBooking> rcBookings = new ArrayList<>();
        int skippedDeleted = 0;

        for (JsonNode ev : events) {
            if (ev.path("is_delete").asBoolean(false)) { skippedDeleted++; continue; }
            if (!"booked".equals(ev.path("status").asText(""))) continue;

            String beginStr = ev.path("begin_date").asText(null);
            String endStr = ev.path("end_date").asText(null);
            if (beginStr == null || endStr == null) continue;

            try {
                LocalDate start = LocalDate.parse(beginStr);
                LocalDate end = LocalDate.parse(endStr);
                long rcId = ev.path("id").asLong(0);
                String guest = ev.path("client").path("fio").asText(null);
                String phone = ev.path("client").path("phone").asText(null);
                double amount = ev.path("amount").asDouble(0);
                int sourceId = ev.path("source_id").asInt(0);

                // Manual RC closure (no client, no amount) — mark as "Ручное закрытие"
                if ((guest == null || guest.isBlank()) && amount == 0.0) {
                    guest = "Ручное закрытие RC";
                }

                rcBookings.add(new RcBooking(rcId, start, end, guest, phone, amount, sourceId));
            } catch (Exception e) {
                log.warn("Cannot parse RC event: {}", e.getMessage());
            }
        }

        log.info("RC sync {}: {} bookings (skipped {} deleted)",
                property.getName(), rcBookings.size(), skippedDeleted);

        if (!rcBookings.isEmpty()) {
            rcSyncService.syncBookings(property, rcBookings);
        }
    }

    /** Данные брони из RC для передачи в RcSyncService */
    public record RcBooking(
            long rcId, LocalDate checkIn, LocalDate checkOut,
            String guestName, String phone, double amount, int sourceId
    ) {}

    private BigDecimal applyAiAdjustment(PricingRecommendation rec,
                                          Map<LocalDate, Double> adjustments, Long tenantId) {
        if (!adjustments.containsKey(rec.date())) return rec.recommendedPrice();
        double multiplier = adjustments.get(rec.date());
        int floor = settings.getIntValue(tenantId, "min_price_floor", 2500);
        int ceil  = settings.getIntValue(tenantId, "max_price_ceiling", 10000);
        int adjusted = (int) Math.round(rec.recommendedPrice().doubleValue() * multiplier);
        adjusted = Math.max(floor, Math.min(ceil, adjusted));
        log.debug("AI adjusted {} from {} to {} (×{})", rec.date(), rec.recommendedPrice(), adjusted, multiplier);
        return BigDecimal.valueOf(adjusted);
    }

    /**
     * Состояние календаря RealtyCalendar.
     */
    private record CalendarState(
            Set<LocalDate> closedDates
    ) {
    }

    /**
     * Рекомендация по цене.
     */
    public record PricingRecommendation(

            LocalDate date,

            BigDecimal currentPrice,

            BigDecimal recommendedPrice,

            int priceDelta,

            int recommendedMinStay,

            BigDecimal netPerNight,

            DayStatus status,

            String reason,

            int confidence
    ) {
    }
}