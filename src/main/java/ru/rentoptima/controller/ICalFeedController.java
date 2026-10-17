package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import ru.rentoptima.channel.ical.ICalWriter;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.ChannelFeedFetch;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.ChannelFeedFetchRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.service.AvailabilityService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Публичная отдача iCal-фида занятости: {@code GET /ical/{secret}.ics}
 * <p>
 * Эндпоинт анонимный — внешние площадки (Airbnb, Booking, Суточно, Островок)
 * не умеют авторизоваться, они просто периодически дёргают URL. Защита строится
 * на неугадываемости секрета (192 бита из SecureRandom) и на том, что в фиде
 * нет ничего чувствительного: только даты занятости и слово «Занято», без имён,
 * телефонов и сумм.
 * <p>
 * Секрет можно перевыпустить в настройках интеграций, если ссылка утекла.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ICalFeedController {

    /** Насколько глубоко в прошлое отдаём занятость. Площадкам история не нужна. */
    private static final int PAST_DAYS = 1;
    private static final int FUTURE_DAYS = 540;

    private final ChannelRepository channelRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final PropertyRepository propertyRepo;
    private final AvailabilityService availability;
    private final ChannelFeedFetchRepository fetchRepo;

    @GetMapping(value = "/ical/{secret}.ics", produces = "text/calendar;charset=UTF-8")
    public ResponseEntity<byte[]> feed(@PathVariable String secret,
                                       @RequestHeader(value = HttpHeaders.USER_AGENT, required = false)
                                       String userAgent) {
        Channel channel = channelRepo.findByExportSecret(secret).orElse(null);

        // Одинаковый 404 и для несуществующего, и для выключенного канала —
        // чтобы по коду ответа нельзя было перебором нащупать валидные секреты.
        if (channel == null || !Boolean.TRUE.equals(channel.getActive())) {
            return ResponseEntity.notFound().build();
        }

        UnitType unitType = unitTypeRepo.findById(channel.getUnitTypeId()).orElse(null);
        if (unitType == null) {
            return ResponseEntity.notFound().build();
        }
        Property property = propertyRepo.findById(unitType.getPropertyId()).orElse(null);

        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(PAST_DAYS);
        LocalDate to = today.plusDays(FUTURE_DAYS);

        // excludeChannelId = этот же канал: не возвращаем площадке её собственную
        // занятость, иначе бронь «залипнет» навсегда — площадка увидит её в нашем
        // фиде, мы увидим её в ответном, и снять такую блокировку станет нечем.
        List<ICalWriter.BusyPeriod> periods =
                availability.exportEvents(unitType, from, to, channel.getId());

        String calendarName = (property != null ? property.getName() : "RentOptima")
                + " — " + unitType.getName();
        String body = ICalWriter.write(calendarName, periods);

        // Отменённые события (удалённые ручные записи) занятостью не считаются
        int busy = (int) periods.stream().filter(p -> !p.cancelled()).count();
        log.debug("iCal feed отдан: channel={}, периодов={}", channel.getId(), busy);
        recordFetch(channel, userAgent, busy);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"rentoptima-" + channel.getId() + ".ics\"")
                // Площадки опрашивают фид часто; пусть держат минутный кэш,
                // но не считают его надолго актуальным.
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
                .body(body.getBytes(StandardCharsets.UTF_8));
    }

    /** Журнал обращений — для диагностики; его сбой не должен ломать отдачу фида. */
    private void recordFetch(Channel channel, String userAgent, int periods) {
        try {
            ChannelFeedFetch fetch = new ChannelFeedFetch();
            fetch.setTenantId(channel.getTenantId());
            fetch.setChannelId(channel.getId());
            fetch.setPeriodsCount(periods);
            if (userAgent != null && !userAgent.isBlank()) {
                fetch.setUserAgent(userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent);
            }
            fetchRepo.save(fetch);
        } catch (Exception e) {
            log.warn("Не удалось записать обращение к фиду канала {}: {}", channel.getId(), e.getMessage());
        }
    }
}
