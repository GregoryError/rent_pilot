package ru.rentoptima.onboarding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.rentoptima.channel.ChannelSyncException;
import ru.rentoptima.channel.ical.ICalEvent;
import ru.rentoptima.channel.ical.ICalFeedFetcher;
import ru.rentoptima.channel.ical.ICalParser;

import java.time.LocalDate;
import java.util.List;

/**
 * Проверка ссылки с площадки сразу при вставке: скачать, разобрать и сказать человеку
 * простыми словами, что получилось. Скачивает тот же {@link ICalFeedFetcher}, что и
 * синхронизация, — с той же защитой от обращений во внутреннюю сеть.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedChecker {

    public enum Outcome { FOUND, EMPTY, NOT_CALENDAR, UNREACHABLE }

    /**
     * @param periods сколько занятых периодов впереди (прошедшие и отменённые не считаются)
     * @param until   день выезда самого дальнего из них
     */
    public record Result(Outcome outcome, int periods, LocalDate until) {
        public boolean ok() {
            return outcome == Outcome.FOUND || outcome == Outcome.EMPTY;
        }
    }

    private final ICalFeedFetcher fetcher;

    public Result check(String url, LocalDate today) {
        String body;
        try {
            body = fetcher.fetch(url);
        } catch (ChannelSyncException | IllegalArgumentException e) {
            log.info("Проверка ссылки площадки: не открылась ({})", e.getMessage());
            return new Result(Outcome.UNREACHABLE, 0, null);
        }
        return read(body, today);
    }

    /** Разбор уже скачанного — отдельно, чтобы тестировать без сети. */
    static Result read(String body, LocalDate today) {
        if (body == null || !body.toUpperCase().contains("BEGIN:VCALENDAR")) {
            return new Result(Outcome.NOT_CALENDAR, 0, null);
        }
        List<ICalEvent> events;
        try {
            events = ICalParser.parse(body);
        } catch (RuntimeException e) {
            return new Result(Outcome.NOT_CALENDAR, 0, null);
        }
        int periods = 0;
        LocalDate until = null;
        for (ICalEvent e : events) {
            if (e.cancelled() || e.end() == null || e.end().isBefore(today)) continue;
            periods++;
            if (until == null || e.end().isAfter(until)) until = e.end();
        }
        return periods == 0 ? new Result(Outcome.EMPTY, 0, null) : new Result(Outcome.FOUND, periods, until);
    }
}
