package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.AlertEvent;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.ChannelFeedFetch;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.AlertEventRepository;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.ChannelFeedFetchRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Диагностика каналов для пилота (блок 4.8): как быстро занятость расходится
 * между нами и площадкой и какие наложения дат при этом возникают.
 * <p>
 * Что измеримо:
 * <ul>
 *   <li><b>наружу</b> — как часто площадка забирает наш экспорт и сколько проходит
 *       от ручной записи в шахматке до ближайшего такого запроса;</li>
 *   <li><b>внутрь</b> — только верхняя граница: наш интервал опроса. Когда бронь
 *       появилась на самой площадке, из iCal не узнать (фиды отдают DTSTAMP —
 *       время генерации файла, а не создания брони), поэтому показываем момент,
 *       когда блокировку впервые увидели мы, — его можно сверить с уведомлением
 *       площадки о брони.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelDiagnosticsService {

    /** Окно статистики и срок хранения журнала обращений. */
    static final int WINDOW_DAYS = 30;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");

    private final ChannelRepository channelRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final PropertyRepository propertyRepo;
    private final CalendarBlockRepository blockRepo;
    private final ChannelFeedFetchRepository fetchRepo;
    private final AlertEventRepository alertRepo;

    public Report build(Long tenantId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(WINDOW_DAYS);

        Map<Long, String> propertyNames = new HashMap<>();
        for (Property p : propertyRepo.findByTenantIdAndActiveTrue(tenantId)) {
            propertyNames.put(p.getId(), p.getName());
        }
        Map<Long, String> unitLabels = new HashMap<>();
        for (UnitType ut : unitTypeRepo.findByTenantIdAndActiveTrue(tenantId)) {
            String property = propertyNames.get(ut.getPropertyId());
            unitLabels.put(ut.getId(), property == null ? ut.getName() : property + " / " + ut.getName());
        }

        List<ChannelReport> channels = new ArrayList<>();
        for (Channel c : channelRepo.findByTenantIdAndActiveTrue(tenantId)) {
            if (c.getChannelType() != Channel.ChannelType.ICAL) continue;
            channels.add(channelReport(c, unitLabels.get(c.getUnitTypeId()), since, now));
        }

        List<JournalRow> journal = alertRepo.findTop50ByTenantIdOrderByFirstSeenAtDesc(tenantId)
                .stream().map(ChannelDiagnosticsService::journalRow).toList();

        return new Report(channels, journal);
    }

    private ChannelReport channelReport(Channel c, String unitLabel,
                                        LocalDateTime since, LocalDateTime now) {
        List<ChannelFeedFetch> fetches =
                fetchRepo.findByChannelIdAndFetchedAtAfterOrderByFetchedAtAsc(c.getId(), since);
        List<LocalDateTime> fetchTimes = fetches.stream().map(ChannelFeedFetch::getFetchedAt).toList();

        LocalDateTime dayAgo = now.minusDays(1);
        long fetches24h = fetchTimes.stream().filter(t -> t.isAfter(dayAgo)).count();
        ChannelFeedFetch last = fetches.isEmpty() ? null : fetches.get(fetches.size() - 1);

        List<Duration> gaps = gaps(fetchTimes);

        List<LocalDateTime> changes = blockRepo
                .findByUnitTypeIdAndChannelIdIsNullAndCreatedAtAfter(c.getUnitTypeId(), since)
                .stream().map(CalendarBlock::getCreatedAt).toList();
        Propagation out = propagation(changes, fetchTimes);

        List<ImportRow> imports = blockRepo.findTop10ByChannelIdOrderByCreatedAtDesc(c.getId())
                .stream()
                .map(b -> new ImportRow(
                        b.getFromDate().format(DAY) + " — " + b.getToDate().format(DAY),
                        b.getCreatedAt().format(TIME)))
                .toList();

        boolean exporting = !fetches.isEmpty();
        return new ChannelReport(
                c.getName(),
                unitLabel == null ? "" : unitLabel,
                ChannelSyncSchedulerService.extractIntervalMinutes(c.getConfigJson()),
                c.getLastSyncAt() == null ? "ещё не было" : c.getLastSyncAt().format(TIME),
                exporting ? "Площадка забирает наш фид" : "Наш фид никто не забирал",
                exporting ? "tag tag--green" : "tag tag--amber",
                fetches.size(),
                fetches24h,
                last == null ? "—" : last.getFetchedAt().format(TIME),
                last == null || last.getUserAgent() == null ? "—" : last.getUserAgent(),
                formatDuration(median(gaps)),
                formatDuration(max(gaps)),
                changes.size(),
                out.delays().size(),
                out.pending(),
                formatDuration(median(out.delays())),
                formatDuration(max(out.delays())),
                imports);
    }

    private static JournalRow journalRow(AlertEvent a) {
        String type = switch (a.getAlertType()) {
            case CONFLICT -> "Конфликт: ручная запись + площадка";
            case OVERLAP -> "Наложение без ручной записи (эхо?)";
            case CHANNEL_DOWN -> "Сбой канала";
        };
        String typeCss = switch (a.getAlertType()) {
            case CONFLICT -> "tag tag--red";
            case OVERLAP -> "tag tag--amber";
            case CHANNEL_DOWN -> "tag tag--muted";
        };
        String lasted = a.getResolvedAt() == null
                ? "открыт"
                : "закрыт через " + formatDuration(Duration.between(a.getFirstSeenAt(), a.getResolvedAt()));
        return new JournalRow(a.getFirstSeenAt().format(TIME), type, typeCss, a.getMessage(), lasted);
    }

    /** Очищает диагностические данные tenant'а. Возвращает число удалённых строк. */
    public int clear(Long tenantId) {
        return fetchRepo.deleteByTenant(tenantId) + alertRepo.deleteClearable(tenantId);
    }

    @Scheduled(cron = "0 30 4 * * *")
    public void pruneFetchLog() {
        int removed = fetchRepo.deleteOlderThan(LocalDateTime.now().minusDays(WINDOW_DAYS));
        if (removed > 0) log.info("Журнал обращений к iCal-экспорту: удалено {} старых записей", removed);
    }

    // --- Чистая арифметика (покрыта тестами)

    /** Интервалы между соседними обращениями. */
    static List<Duration> gaps(List<LocalDateTime> sortedTimes) {
        List<Duration> gaps = new ArrayList<>();
        for (int i = 1; i < sortedTimes.size(); i++) {
            gaps.add(Duration.between(sortedTimes.get(i - 1), sortedTimes.get(i)));
        }
        return gaps;
    }

    /**
     * Для каждого изменения занятости — сколько прошло до первого обращения площадки
     * к фиду после него. Изменения, после которых фид ещё не забирали, идут в pending.
     */
    static Propagation propagation(List<LocalDateTime> changes, List<LocalDateTime> sortedFetches) {
        List<Duration> delays = new ArrayList<>();
        int pending = 0;
        for (LocalDateTime change : changes) {
            LocalDateTime next = null;
            for (LocalDateTime fetch : sortedFetches) {
                if (!fetch.isBefore(change)) {
                    next = fetch;
                    break;
                }
            }
            if (next == null) pending++;
            else delays.add(Duration.between(change, next));
        }
        return new Propagation(delays, pending);
    }

    static Duration median(List<Duration> values) {
        if (values.isEmpty()) return null;
        List<Duration> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(mid)
                : sorted.get(mid - 1).plus(sorted.get(mid)).dividedBy(2);
    }

    static Duration max(List<Duration> values) {
        return values.isEmpty() ? null : Collections.max(values);
    }

    static String formatDuration(Duration d) {
        if (d == null) return "—";
        long minutes = d.toMinutes();
        if (minutes < 1) return "меньше минуты";
        if (minutes < 60) return minutes + " мин";
        long hours = minutes / 60;
        if (hours < 24) return hours + " ч " + (minutes % 60) + " мин";
        return (hours / 24) + " д " + (hours % 24) + " ч";
    }

    record Propagation(List<Duration> delays, int pending) {}

    public record Report(List<ChannelReport> channels, List<JournalRow> journal) {}

    public record ChannelReport(String name, String unitLabel,
                                int syncIntervalMinutes, String lastSync,
                                String exportStatus, String exportStatusCss,
                                int fetchesTotal, long fetches24h,
                                String lastFetch, String lastFetchAgent,
                                String gapMedian, String gapMax,
                                int manualChanges, int measuredChanges, int pendingChanges,
                                String outMedian, String outMax,
                                List<ImportRow> imports) {}

    public record ImportRow(String dates, String firstSeen) {}

    public record JournalRow(String time, String type, String typeCss,
                             String message, String lasted) {}
}
