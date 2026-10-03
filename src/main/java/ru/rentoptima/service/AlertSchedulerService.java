package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.AlertEvent;
import ru.rentoptima.entity.AlertEvent.AlertType;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.AlertEventRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.service.AvailabilityService.DayOccupancy;
import ru.rentoptima.service.ConflictDetector.ConflictPeriod;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Детектор конфликтов и падений каналов + уведомления в Telegram (блок 4.7).
 * <p>
 * Раз в несколько минут сверяет текущие проблемы tenant'а с открытыми алертами
 * в {@code alert_events}: новые заводит, исчезнувшие закрывает. В Telegram уходит
 * только то, что ещё не доставлено, — одна и та же проблема не шлётся повторно.
 * <p>
 * Только уведомляет: ничего не правит в бронях и не пушит на площадки.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertSchedulerService {

    /** На сколько дней вперёд ищем пересечения — столько же каналы тянут из iCal. */
    static final int HORIZON_DAYS = 365;
    /** Столько ошибок синхронизации подряд считаем падением канала. */
    static final int CHANNEL_DOWN_THRESHOLD = 3;
    /** Больше пунктов в одно сообщение не кладём — остальное сводим в «и ещё N». */
    private static final int MAX_ITEMS_PER_MESSAGE = 10;
    private static final String DEFAULT_BASE_URL = "https://optirent.ru";

    private final UnitTypeRepository unitTypeRepo;
    private final PropertyRepository propertyRepo;
    private final ChannelRepository channelRepo;
    private final AlertEventRepository alertRepo;
    private final AvailabilityService availability;
    private final TelegramService telegram;
    private final SettingsService settings;

    @Scheduled(fixedDelayString = "${app.alerts.interval-ms:300000}",
               initialDelayString = "${app.alerts.initial-delay-ms:90000}")
    public void tick() {
        Map<Long, List<UnitType>> byTenant = new LinkedHashMap<>();
        for (UnitType ut : unitTypeRepo.findByActiveTrue()) {
            byTenant.computeIfAbsent(ut.getTenantId(), k -> new ArrayList<>()).add(ut);
        }
        for (Map.Entry<Long, List<UnitType>> e : byTenant.entrySet()) {
            try {
                checkTenant(e.getKey(), e.getValue());
            } catch (Exception ex) {
                log.warn("Alert check failed for tenant {}: {}", e.getKey(), ex.getMessage());
            }
        }
    }

    /** Один прогон для tenant'а. Вызывается и вручную — кнопкой «Проверить сейчас». */
    public void checkTenant(Long tenantId) {
        checkTenant(tenantId, unitTypeRepo.findByTenantIdAndActiveTrue(tenantId));
    }

    private void checkTenant(Long tenantId, List<UnitType> unitTypes) {
        Map<Long, String> propertyNames = new HashMap<>();
        for (Property p : propertyRepo.findByTenantIdAndActiveTrue(tenantId)) {
            propertyNames.put(p.getId(), p.getName());
        }
        // Категории выключенных объектов в шахматке не видны — и алертов по ним не шлём
        Map<Long, UnitType> activeUnits = new LinkedHashMap<>();
        Map<Long, String> unitLabels = new HashMap<>();
        for (UnitType ut : unitTypes) {
            String property = propertyNames.get(ut.getPropertyId());
            if (property == null) continue;
            activeUnits.put(ut.getId(), ut);
            unitLabels.put(ut.getId(), property + " / " + ut.getName());
        }

        List<Channel> channels = channelRepo.findByTenantIdAndActiveTrue(tenantId);
        Map<Long, String> channelNames = new HashMap<>();
        for (Channel c : channels) channelNames.put(c.getId(), c.getName());

        List<AlertEvent> current = new ArrayList<>();
        current.addAll(findConflicts(tenantId, activeUnits, unitLabels, channelNames));
        current.addAll(findDownChannels(tenantId, channels, unitLabels));

        Reconciled reconciled = reconcile(tenantId, current);
        sendNotifications(tenantId, reconciled);
    }

    private List<AlertEvent> findConflicts(Long tenantId, Map<Long, UnitType> units,
                                           Map<Long, String> unitLabels,
                                           Map<Long, String> channelNames) {
        List<AlertEvent> found = new ArrayList<>();
        if (units.isEmpty()) return found;

        LocalDate today = LocalDate.now();
        Map<Long, Map<LocalDate, DayOccupancy>> occupancy = availability.occupancyDetails(
                new ArrayList<>(units.keySet()), today, today.plusDays(HORIZON_DAYS));

        for (UnitType ut : units.values()) {
            int capacity = ut.getUnitCount() == null ? 1 : Math.max(1, ut.getUnitCount());
            Map<LocalDate, DayOccupancy> days = occupancy.getOrDefault(ut.getId(), Map.of());
            for (ConflictPeriod p : ConflictDetector.findPeriods(ut.getId(), capacity, days, today)) {
                AlertEvent a = new AlertEvent();
                a.setTenantId(tenantId);
                a.setAlertType(AlertType.CONFLICT);
                a.setDedupKey(p.dedupKey());
                a.setUnitTypeId(ut.getId());
                a.setFromDate(p.from());
                a.setToDate(p.toExclusive());
                a.setMessage(conflictText(unitLabels.get(ut.getId()), p, channelNames));
                found.add(a);
            }
        }
        return found;
    }

    private List<AlertEvent> findDownChannels(Long tenantId, List<Channel> channels,
                                              Map<Long, String> unitLabels) {
        List<AlertEvent> found = new ArrayList<>();
        for (Channel c : channels) {
            if (!Boolean.TRUE.equals(c.getSyncEnabled())) continue;
            int errors = c.getConsecutiveErrors() == null ? 0 : c.getConsecutiveErrors();
            if (errors < CHANNEL_DOWN_THRESHOLD) continue;

            AlertEvent a = new AlertEvent();
            a.setTenantId(tenantId);
            a.setAlertType(AlertType.CHANNEL_DOWN);
            a.setDedupKey("ch" + c.getId());
            a.setChannelId(c.getId());
            a.setUnitTypeId(c.getUnitTypeId());
            a.setMessage(channelDownText(c, unitLabels.get(c.getUnitTypeId()), errors));
            found.add(a);
        }
        return found;
    }

    /** Сверяет найденное с открытыми алертами: новые сохраняет, исчезнувшие закрывает. */
    private Reconciled reconcile(Long tenantId, List<AlertEvent> current) {
        Map<String, AlertEvent> open = new HashMap<>();
        for (AlertEvent a : alertRepo.findByTenantIdAndResolvedAtIsNull(tenantId)) {
            open.put(a.getAlertType() + ":" + a.getDedupKey(), a);
        }

        LocalDateTime now = LocalDateTime.now();
        List<AlertEvent> stillOpen = new ArrayList<>();
        for (AlertEvent found : current) {
            AlertEvent existing = open.remove(found.getAlertType() + ":" + found.getDedupKey());
            if (existing == null) {
                stillOpen.add(alertRepo.save(found));
                continue;
            }
            existing.setLastSeenAt(now);
            // Начало конфликта сдвигается вслед за «сегодня», число ошибок канала растёт
            existing.setFromDate(found.getFromDate());
            existing.setMessage(found.getMessage());
            stillOpen.add(alertRepo.save(existing));
        }

        List<AlertEvent> resolved = new ArrayList<>();
        for (AlertEvent gone : open.values()) {
            gone.setResolvedAt(now);
            resolved.add(alertRepo.save(gone));
        }
        return new Reconciled(stillOpen, resolved);
    }

    private void sendNotifications(Long tenantId, Reconciled reconciled) {
        if (!telegram.isConfigured(tenantId)) return;

        List<AlertEvent> pending = reconciled.open().stream()
                .filter(a -> a.getNotifiedAt() == null)
                .toList();
        if (!pending.isEmpty()) {
            String text = composeMessage(pending, baseUrl(tenantId));
            if (telegram.send(tenantId, text).ok()) {
                LocalDateTime now = LocalDateTime.now();
                for (AlertEvent a : pending) {
                    a.setNotifiedAt(now);
                    alertRepo.save(a);
                }
            }
            // Не доставили — notified_at остаётся пустым, повторим на следующем прогоне
        }

        // Про восстановление пишем только по каналам и только если о падении сообщали.
        // Исчезнувший конфликт видно в шахматке, отдельное сообщение — лишний шум.
        List<String> recovered = reconciled.resolved().stream()
                .filter(a -> a.getAlertType() == AlertType.CHANNEL_DOWN && a.getNotifiedAt() != null)
                .map(a -> "✅ " + recoveredText(a))
                .toList();
        if (!recovered.isEmpty()) {
            telegram.send(tenantId, String.join("\n", recovered));
        }
    }

    private String baseUrl(Long tenantId) {
        String url = settings.getValue(tenantId, TelegramService.KEY_BASE_URL);
        return url == null || url.isBlank() ? DEFAULT_BASE_URL : url;
    }

    // --- Тексты

    static String composeMessage(List<AlertEvent> alerts, String baseUrl) {
        List<AlertEvent> conflicts = alerts.stream()
                .filter(a -> a.getAlertType() == AlertType.CONFLICT).toList();
        List<AlertEvent> down = alerts.stream()
                .filter(a -> a.getAlertType() == AlertType.CHANNEL_DOWN).toList();

        StringBuilder sb = new StringBuilder();
        if (!conflicts.isEmpty()) {
            sb.append("⚠️ OptiRent: пересечение броней\n");
            appendItems(sb, conflicts);
            sb.append("\nРучная запись и занятость с площадки на одни даты — возможна двойная бронь.\n");
            LocalDate first = conflicts.get(0).getFromDate();
            sb.append("Шахматка: ").append(baseUrl).append("/calendar/grid?from=")
                    .append(first).append("&days=30\n");
        }
        if (!down.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("🔌 OptiRent: канал не синхронизируется\n");
            appendItems(sb, down);
            sb.append("\nЗанятость с этой площадки не обновляется — проверьте ссылку iCal.\n");
            sb.append("Каналы: ").append(baseUrl).append("/settings/channels\n");
        }
        return sb.toString().trim();
    }

    private static void appendItems(StringBuilder sb, List<AlertEvent> alerts) {
        int shown = Math.min(alerts.size(), MAX_ITEMS_PER_MESSAGE);
        for (int i = 0; i < shown; i++) {
            sb.append("• ").append(alerts.get(i).getMessage()).append("\n");
        }
        if (alerts.size() > shown) {
            sb.append("… и ещё ").append(alerts.size() - shown).append("\n");
        }
    }

    static String conflictText(String unitLabel, ConflictPeriod p, Map<Long, String> channelNames) {
        List<String> names = new ArrayList<>();
        for (Long id : p.channelIds()) {
            String name = channelNames.get(id);
            if (name != null) names.add("«" + name + "»");
        }
        long nights = ChronoUnit.DAYS.between(p.from(), p.toExclusive());
        String text = (unitLabel == null ? "Категория" : unitLabel)
                + ": " + formatNights(p.from(), p.toExclusive())
                + " (" + nights + " " + nightsWord(nights) + ")";
        return names.isEmpty() ? text : text + " — " + String.join(", ", names);
    }

    private static String channelDownText(Channel c, String unitLabel, int errors) {
        String text = "«" + c.getName() + "»"
                + (unitLabel == null ? "" : " (" + unitLabel + ")")
                + ": ошибок подряд — " + errors;
        String error = c.getLastError();
        if (error == null || error.isBlank()) return text;
        return text + ". Последняя: " + (error.length() > 200 ? error.substring(0, 200) + "…" : error);
    }

    /** Из текста алерта о падении берём только название канала с объектом. */
    private static String recoveredText(AlertEvent a) {
        String message = a.getMessage();
        int cut = message.indexOf(": ");
        return (cut > 0 ? message.substring(0, cut) : "Канал") + " — синхронизация восстановилась";
    }

    /** Ночи периода человеческим языком: «12 октября», «12–14 октября», «30 октября – 2 ноября». */
    static String formatNights(LocalDate from, LocalDate toExclusive) {
        LocalDate last = toExclusive.minusDays(1);
        String lastText = last.getDayOfMonth() + " " + OccupancyGridService.russianMonthGen(last.getMonthValue());
        if (!last.isAfter(from)) return lastText;
        if (from.getMonth() == last.getMonth() && from.getYear() == last.getYear()) {
            return from.getDayOfMonth() + "–" + lastText;
        }
        return from.getDayOfMonth() + " " + OccupancyGridService.russianMonthGen(from.getMonthValue())
                + " – " + lastText;
    }

    private static String nightsWord(long n) {
        long mod100 = n % 100;
        long mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return "ночей";
        if (mod10 == 1) return "ночь";
        if (mod10 >= 2 && mod10 <= 4) return "ночи";
        return "ночей";
    }

    private record Reconciled(List<AlertEvent> open, List<AlertEvent> resolved) {}
}
