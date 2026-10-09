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
import ru.rentoptima.entity.ManualBlockEcho;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.ManualBlockEchoRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * и создаётся только каналами с полноценным API (позже Avito).
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
 * <p>
 * <b>Эхо ручных записей.</b> Ручная запись уходит в экспорт всех каналов, а площадки
 * (и channel manager'ы за ними) возвращают импортированное обратно. Блокировку по
 * такому событию создавать нельзя: она переживёт удаление исходной записи и будет
 * возвращаться при каждой синхронизации, а через второй канал — поддерживать сама
 * себя. Эхо узнаём двумя способами:
 * <ul>
 *   <li>по UID-маркеру {@code optirent-manual-<id>} — площадка сохранила наш UID;</li>
 *   <li>по датам — площадка выдала свой UID: событие новое для нас, а его даты
 *       совпадают с живой ручной записью, заведённой не раньше
 *       {@link #ECHO_MATCH_WINDOW_DAYS} дней назад. Только для категорий с одной
 *       единицей: в мини-отеле те же даты у другого номера — обычное дело, а не эхо.</li>
 * </ul>
 * Событие с UID-маркером — эхо наверняка, оно не импортируется. Совпадение дат —
 * только догадка: это может быть и настоящая бронь с площадки. Поэтому такое событие
 * импортируется обычной блокировкой с пометкой {@code shadow_of_manual_id} («тень»).
 * Пока ручная запись жива, AvailabilityService тень не показывает и не считает; если
 * ручную запись удалят, тень останется и будет держать даты, пока событие есть в фиде
 * площадки. В обоих случаях связь запоминается в {@code manual_block_echoes} — для
 * предупреждения при удалении ручной записи. Это дополнение к anti-echo по channel_id
 * в экспорте, а не замена ему.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ICalChannelAdapter implements ChannelAdapter {

    /**
     * Сколько дней после создания ручной записи совпадение дат с новым событием канала
     * считается эхом. Позже — это уже настоящая бронь на те же даты. Константа на
     * время MVP; при необходимости выносится в настройку tenant'а.
     */
    static final int ECHO_MATCH_WINDOW_DAYS = 7;

    private final ICalFeedFetcher fetcher;
    private final CalendarBlockRepository blockRepo;
    private final ManualBlockEchoRepository echoRepo;

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

        Map<String, ManualBlockEcho> echoes = new HashMap<>();
        for (ManualBlockEcho e : echoRepo.findByChannelId(channel.getId())) {
            echoes.put(e.getExternalUid(), e);
        }
        Integer unitCount = ctx.unitType() == null ? null : ctx.unitType().getUnitCount();
        boolean singleUnit = unitCount == null || unitCount <= 1;
        List<CalendarBlock> recentManual = singleUnit
                ? blockRepo.findByUnitTypeIdAndChannelIdIsNullAndCreatedAtAfter(
                        ctx.unitTypeId(), LocalDateTime.now().minusDays(ECHO_MATCH_WINDOW_DAYS))
                : List.of();

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

            // Эхо с нашим UID в seenUids не попадает: если по нему раньше успела
            // создаться блокировка, сверка ниже её снимет.
            if (ev.uid().startsWith(CalendarBlock.MANUAL_UID_PREFIX)) {
                if (!echoes.containsKey(ev.uid())) linkOwnUid(ctx, ev, echoes);
                log.info("Skipped echo of own MANUAL booking: external_uid={}, channel={}",
                        ev.uid(), channel.getId());
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
                if (changed && b.getShadowOfManualId() != null) {
                    // Даты на площадке сдвинули — это уже не копия ручной записи
                    log.info("iCal channel {}: блокировка uid={} больше не тень ручной записи {} — даты изменились",
                            channel.getId(), ev.uid(), b.getShadowOfManualId());
                    b.setShadowOfManualId(null);
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

            // Событие для нас новое. Блокировки, импортированные раньше, сюда не
            // доходят и тенью задним числом не становятся: бронь, которая пришла с
            // площадки до ручной записи, — настоящая.
            ManualBlockEcho known = echoes.get(ev.uid());
            CalendarBlock manual = known != null
                    ? linkedManual(known, ev)
                    : matchRecentManual(recentManual, ev);
            if (manual != null) {
                if (known == null) echoes.put(ev.uid(), saveEcho(ctx, manual, ev.uid()));
                log.info("Linked echo from channel {} (UID={}) to MANUAL block {}",
                        channel.getId(), ev.uid(), manual.getId());
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
            if (manual != null) b.setShadowOfManualId(manual.getId());
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

    /**
     * Площадка вернула событие с нашим UID-маркером: запоминаем, что ручную запись
     * видели на этом канале (нужно для предупреждения при её удалении). Если запись
     * по id из UID не находится или она чужая — связь не создаём, событие всё равно
     * пропускается.
     */
    private void linkOwnUid(ChannelContext ctx, ICalEvent ev, Map<String, ManualBlockEcho> echoes) {
        String tail = ev.uid().substring(CalendarBlock.MANUAL_UID_PREFIX.length());
        int at = tail.indexOf('@');
        Long blockId;
        try {
            blockId = Long.valueOf(at >= 0 ? tail.substring(0, at) : tail);
        } catch (NumberFormatException e) {
            return;
        }
        CalendarBlock manual = blockRepo.findById(blockId).orElse(null);
        if (manual == null || !manual.isHandMade()
                || !Objects.equals(manual.getTenantId(), ctx.tenantId())
                || !Objects.equals(manual.getUnitTypeId(), ctx.unitTypeId())) {
            return;
        }
        echoes.put(ev.uid(), saveEcho(ctx, manual, ev.uid()));
        log.info("Linked echo from channel {} (UID={}) to MANUAL block {}",
                ctx.channel().getId(), ev.uid(), manual.getId());
    }

    private ManualBlockEcho saveEcho(ChannelContext ctx, CalendarBlock manual, String uid) {
        ManualBlockEcho echo = new ManualBlockEcho();
        echo.setTenantId(ctx.tenantId());
        echo.setManualBlockId(manual.getId());
        echo.setChannelId(ctx.channel().getId());
        echo.setExternalUid(uid);
        echoRepo.save(echo);
        return echo;
    }

    /**
     * Событие уже было привязано к ручной записи, но блокировки по нему у нас нет
     * (исчезало из фида и вернулось). Тенью оно становится снова, если запись жива и
     * даты по-прежнему совпадают, — независимо от её возраста.
     */
    private CalendarBlock linkedManual(ManualBlockEcho echo, ICalEvent ev) {
        return blockRepo.findById(echo.getManualBlockId())
                .filter(m -> sameDates(m, ev))
                .orElse(null);
    }

    /** Живая ручная запись с теми же датами. */
    private static CalendarBlock matchRecentManual(List<CalendarBlock> recentManual, ICalEvent ev) {
        for (CalendarBlock m : recentManual) {
            if (sameDates(m, ev)) return m;
        }
        return null;
    }

    private static boolean sameDates(CalendarBlock manual, ICalEvent ev) {
        return manual.isHandMade() && manual.getCancelledAt() == null
                && ev.start().equals(manual.getFromDate()) && ev.end().equals(manual.getToDate());
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
