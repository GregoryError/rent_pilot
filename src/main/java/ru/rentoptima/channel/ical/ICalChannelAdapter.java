package ru.rentoptima.channel.ical;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.channel.ChannelAdapter;
import ru.rentoptima.channel.ChannelContext;
import ru.rentoptima.channel.ChannelSyncResult;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.repository.CalendarBlockRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Канал на базе iCal: импорт занятости с внешней площадки и экспорт своей.
 * <p>
 * <b>Почему импортированные события становятся CalendarBlock, а не Booking.</b>
 * iCal-фид площадки несёт только интервал занятости: ни суммы, ни комиссии,
 * ни гостя там нет (а если бы и были — класть ПДн в публичный фид нельзя).
 * Создавать из такого события Booking значило бы засорять выручку и статистику
 * нулевыми бронями и ломать отчёты. Booking остаётся сущностью, несущей деньги,
 * и создаётся только каналами с полноценным API (RC, позже Avito).
 * CalendarBlock же ровно для того и заведён — «unit type занят, подробности
 * неизвестны». Шахматка показывает и то, и другое.
 * <p>
 * <b>Идемпотентность.</b> Ключ — (channel_id, external_uid), защищён уникальным
 * индексом из V17. Повторный прогон на том же фиде не создаёт дубликатов:
 * существующая блокировка обновляется, только если реально поменялись даты
 * или текст.
 * <p>
 * <b>Reconcile.</b> Блокировки этого канала, попадающие в окно синхронизации,
 * но отсутствующие в свежем фиде (или помеченные STATUS:CANCELLED), удаляются —
 * гость отменил бронь на площадке, даты должны освободиться. За пределами окна
 * ничего не трогаем: фид обычно отдаёт ограниченный горизонт, и «тишина» за его
 * краем не означает отмену.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ICalChannelAdapter implements ChannelAdapter {

    private final ICalFeedFetcher fetcher;
    private final CalendarBlockRepository blockRepo;

    @Override
    public Channel.ChannelType type() {
        return Channel.ChannelType.ICAL;
    }

    @Override
    public boolean supportsIcalExport() {
        return true;
    }

    @Override
    @Transactional
    public ChannelSyncResult pull(ChannelContext ctx) {
        Channel channel = ctx.channel();
        String importUrl = configText(channel, "import_url");

        if (importUrl == null || importUrl.isBlank()) {
            // Канал настроен только на экспорт — это нормальный режим, не ошибка.
            log.debug("iCal channel {} без import_url — только экспорт", channel.getId());
            return ChannelSyncResult.empty();
        }

        String ics = fetcher.fetch(importUrl);
        List<ICalEvent> events = ICalParser.parse(ics);
        log.info("iCal channel {}: получено {} событий из фида", channel.getId(), events.size());

        return reconcile(ctx, events);
    }

    /**
     * Приводит локальные блокировки канала в соответствие со списком событий.
     * Выделен отдельным методом, чтобы тестировать логику сверки без сети.
     */
    ChannelSyncResult reconcile(ChannelContext ctx, List<ICalEvent> events) {
        Channel channel = ctx.channel();
        LocalDate from = ctx.from();
        LocalDate to = ctx.to();

        int imported = 0, updated = 0, removed = 0, skipped = 0;
        Set<String> seenUids = new HashSet<>();

        for (ICalEvent ev : events) {
            // Событие вне окна синхронизации — не наша забота на этом прогоне.
            if (!ev.start().isBefore(to) || !ev.end().isAfter(from)) {
                skipped++;
                continue;
            }
            if (ev.cancelled()) {
                // Отменённые не добавляем в seenUids — ниже их блокировки снимутся.
                skipped++;
                continue;
            }
            seenUids.add(ev.uid());

            Optional<CalendarBlock> existing =
                    blockRepo.findByChannelIdAndExternalUid(channel.getId(), ev.uid());

            if (existing.isPresent()) {
                CalendarBlock b = existing.get();
                boolean changed = false;
                if (!Objects.equals(b.getFromDate(), ev.start())) {
                    b.setFromDate(ev.start());
                    changed = true;
                }
                if (!Objects.equals(b.getToDate(), ev.end())) {
                    b.setToDate(ev.end());
                    changed = true;
                }
                String reason = reasonOf(ev, channel);
                if (!Objects.equals(b.getReason(), reason)) {
                    b.setReason(reason);
                    changed = true;
                }
                if (changed) {
                    b.setUpdatedAt(LocalDateTime.now());
                    blockRepo.save(b);
                    updated++;
                }
                continue;
            }

            CalendarBlock b = new CalendarBlock();
            b.setTenantId(ctx.tenantId());
            b.setUnitTypeId(ctx.unitTypeId());
            b.setChannelId(channel.getId());
            b.setExternalUid(ev.uid());
            b.setBlockType(CalendarBlock.BlockType.CHANNEL_SYNC);
            b.setFromDate(ev.start());
            b.setToDate(ev.end());
            b.setReason(reasonOf(ev, channel));
            blockRepo.save(b);
            imported++;
        }

        // Снимаем то, что исчезло из фида в пределах окна.
        for (CalendarBlock b : blockRepo.findByChannelInRange(channel.getId(), from, to)) {
            if (b.getExternalUid() == null) continue;      // ручная — не трогаем
            if (seenUids.contains(b.getExternalUid())) continue;
            blockRepo.delete(b);
            removed++;
            log.debug("iCal channel {}: снята блокировка uid={}", channel.getId(), b.getExternalUid());
        }

        return new ChannelSyncResult(imported, updated, removed, skipped);
    }

    private String reasonOf(ICalEvent ev, Channel channel) {
        String s = ev.summary();
        if (s == null || s.isBlank()) return channel.getName();
        return s.length() > 255 ? s.substring(0, 255) : s;
    }

    private static String configText(Channel channel, String field) {
        JsonNode cfg = channel.getConfigJson();
        if (cfg == null) return null;
        JsonNode n = cfg.get(field);
        return (n == null || n.isNull()) ? null : n.asText();
    }
}
