package ru.rentoptima.service;

import ru.rentoptima.service.AvailabilityService.DayOccupancy;
import ru.rentoptima.service.AvailabilityService.Occupant;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Склеивает конфликтные дни категории в периоды — для алертов.
 * <p>
 * Правило конфликта то же, что у красной рамки в шахматке
 * ({@link DayOccupancy#conflict}): алерт и сетка не должны расходиться.
 */
public final class ConflictDetector {

    private ConflictDetector() {
    }

    /**
     * @param days  занятость категории по дням (из AvailabilityService.occupancyDetails)
     * @param today дни раньше не рассматриваются: прошедшее пересечение уже не исправить
     */
    public static List<ConflictPeriod> findPeriods(Long unitTypeId, int capacity,
                                                   Map<LocalDate, DayOccupancy> days,
                                                   LocalDate today) {
        List<ConflictPeriod> periods = new ArrayList<>();
        LocalDate start = null;
        LocalDate prev = null;
        Set<Long> channels = new LinkedHashSet<>();

        for (Map.Entry<LocalDate, DayOccupancy> e : new TreeMap<>(days).entrySet()) {
            LocalDate d = e.getKey();
            if (d.isBefore(today) || !e.getValue().conflict(capacity)) continue;

            if (start != null && !d.equals(prev.plusDays(1))) {
                periods.add(new ConflictPeriod(unitTypeId, start, prev.plusDays(1), channels));
                start = null;
                channels = new LinkedHashSet<>();
            }
            if (start == null) start = d;
            prev = d;
            for (Occupant o : e.getValue().occupants()) {
                if (!o.manual() && o.channelId() != null) channels.add(o.channelId());
            }
        }
        if (start != null) {
            periods.add(new ConflictPeriod(unitTypeId, start, prev.plusDays(1), channels));
        }
        return periods;
    }

    /**
     * @param from        первая конфликтная ночь
     * @param toExclusive день после последней конфликтной ночи
     * @param channelIds  каналы, чья занятость пересеклась с ручной записью
     *                    (пусто, если внешняя сторона — RC или бронь без канала)
     */
    public record ConflictPeriod(Long unitTypeId, LocalDate from, LocalDate toExclusive,
                                 Set<Long> channelIds) {

        /**
         * Ключ дедупликации. Начало периода в ключ не входит намеренно: оно каждый
         * день сдвигается вслед за «сегодня», и тот же конфликт уходил бы в Telegram
         * заново каждое утро.
         */
        public String dedupKey() {
            return "ut" + unitTypeId + ":" + toExclusive;
        }
    }
}
