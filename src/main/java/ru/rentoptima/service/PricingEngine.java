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

    /**
     * Автопилот запускается каждый час.
     */
//    @Scheduled(fixedDelay = 3600000)
    @Scheduled(fixedDelay = (60*1000)*3)
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
    private void runForProperty(
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
                            rec.recommendedPrice(),
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
                        from,
                        to
                );

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
             * -----------------------------------------------------
             * Проверка локальной брони
             * -----------------------------------------------------
             */

            boolean isBooked =
                    bookings.stream().anyMatch(b ->
                            !d.isBefore(b.getCheckIn())
                                    && d.isBefore(b.getCheckOut())
                    );

            if (isBooked) {

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

            boolean isWeekend =
                    d.getDayOfWeek().getValue() >= 5;

            boolean isHoliday =
                    prodCalendar.isHoliday(d);

            boolean isGap =
                    gapDates.contains(d);

            int basePrice =
                    (isWeekend || isHoliday)
                            ? weekendBase
                            : weekdayBase;

            double multiplier;
            int minStay;
            String reason;
            int confidence;

            if (daysAhead > 30) {

                multiplier = 1.0;
                minStay = maxMinStay;

                reason =
                        "Далеко (" +
                                daysAhead +
                                " дн.) — soft lock";

                confidence = 55;

            } else if (daysAhead > 14) {

                multiplier = 1.0;
                minStay = isWeekend ? 2 : 5;

                reason =
                        "Стандартный период";

                confidence = 65;

            } else if (daysAhead > 7) {

                multiplier = 0.97;
                minStay = isWeekend ? 1 : 3;

                reason =
                        "2 недели — снижаем мин. срок";

                confidence = 72;

            } else if (daysAhead > 3) {

                multiplier = 0.93;
                minStay = isWeekend ? 1 : 2;

                reason =
                        "Неделя — снижаем цену " +
                                "и мин. срок";

                confidence = 80;

            } else {

                multiplier = 0.87;
                minStay = 1;

                reason =
                        "3 дня — минимум";

                confidence = 88;
            }

            /*
             * Gap.
             */
            if (isGap) {

                multiplier *= 0.90;
                minStay = 1;

                reason =
                        "Gap — агрессивная скидка";

                confidence = 92;
            }

            /*
             * Праздник.
             */
            if (isHoliday) {

                multiplier *= 1.10;

                reason += " + праздник";
            }

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
             * Цена гостя.
             */
            BigDecimal guestPrice =
                    BigDecimal
                            .valueOf(rcPrice)
                            .multiply(
                                    BigDecimal.valueOf(
                                            1 + markupPct
                                    )
                            )
                            .setScale(
                                    0,
                                    RoundingMode.HALF_UP
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