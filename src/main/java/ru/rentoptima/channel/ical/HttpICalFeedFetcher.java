package ru.rentoptima.channel.ical;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.rentoptima.channel.ChannelSyncException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Боевая реализация загрузчика iCal поверх java.net.http.
 * <p>
 * Ограничения выставлены осознанно: внешняя площадка — недоверенный источник,
 * и синхронизация не должна вешать пул планировщика или съедать память.
 */
@Slf4j
@Component
public class HttpICalFeedFetcher implements ICalFeedFetcher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    /** 5 МБ — заведомо больше любого календаря занятости и защищает от «бесконечного» ответа. */
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String fetch(String url) {
        if (url == null || url.isBlank()) {
            throw new ChannelSyncException("Не задан import_url канала");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new ChannelSyncException("Некорректный import_url: " + url, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        // webcal:// — распространённая схема в ссылках площадок, трактуем как https
        if (scheme.equals("webcal")) {
            uri = URI.create("https://" + url.trim().substring("webcal://".length()));
        } else if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ChannelSyncException("Недопустимая схема URL: " + scheme);
        }

        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(READ_TIMEOUT)
                    .header("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.5")
                    .header("User-Agent", "RentOptima-ChannelSync/1.0")
                    .GET()
                    .build();

            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new ChannelSyncException(
                        "Фид вернул HTTP " + resp.statusCode() + " для " + uri.getHost());
            }
            byte[] body = resp.body();
            if (body.length > MAX_BYTES) {
                throw new ChannelSyncException(
                        "Фид больше допустимых " + MAX_BYTES + " байт");
            }
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);

        } catch (ChannelSyncException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelSyncException("Загрузка фида прервана", e);
        } catch (Exception e) {
            throw new ChannelSyncException(
                    "Не удалось загрузить фид: " + e.getMessage(), e);
        }
    }
}
