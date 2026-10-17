package ru.rentoptima.channel.ical;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.rentoptima.channel.ChannelSyncException;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Загрузка iCal-фида поверх java.net.http с SSRF-защитой.
 * <p>
 * Автоматические редиректы выключены; каждый Location вручную прогоняется через
 * UrlSafetyGuard. Все адреса резолва проверяются против чёрного списка приватных
 * диапазонов.
 */
@Slf4j
@Component
public class HttpICalFeedFetcher implements ICalFeedFetcher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public String fetch(String url) {
        URI uri = UrlSafetyGuard.normalizeAndCheckShape(url);
        return fetchWithRedirects(uri, 0);
    }

    private String fetchWithRedirects(URI uri, int hop) {
        if (hop > MAX_REDIRECTS) {
            throw new ChannelSyncException("Слишком много редиректов: " + hop);
        }
        InetAddress resolved = UrlSafetyGuard.resolveAndCheck(uri.getHost());
        log.debug("iCal fetch: {} -> {} (hop {})", uri, resolved.getHostAddress(), hop);

        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(READ_TIMEOUT)
                    .header("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.5")
                    .header("User-Agent", "RentOptima-ChannelSync/1.0")
                    .GET()
                    .build();

            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();

            if (code >= 300 && code < 400) {
                Optional<String> location = resp.headers().firstValue("Location");
                if (location.isEmpty()) {
                    throw new ChannelSyncException("HTTP " + code + " без Location");
                }
                URI next = resolveLocation(uri, location.get());
                URI safeNext = UrlSafetyGuard.normalizeAndCheckShape(next.toString());
                return fetchWithRedirects(safeNext, hop + 1);
            }

            if (code / 100 != 2) {
                throw new ChannelSyncException("Фид вернул HTTP " + code + " для " + uri.getHost());
            }

            byte[] body = resp.body();
            if (body.length > MAX_BYTES) {
                throw new ChannelSyncException("Фид больше " + MAX_BYTES + " байт");
            }
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);

        } catch (ChannelSyncException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelSyncException("Загрузка фида прервана", e);
        } catch (Exception e) {
            throw new ChannelSyncException("Не удалось загрузить фид: " + e.getMessage(), e);
        }
    }

    private static URI resolveLocation(URI base, String location) {
        try {
            return base.resolve(location.trim());
        } catch (IllegalArgumentException e) {
            throw new ChannelSyncException("Некорректный Location: " + location);
        }
    }
}
