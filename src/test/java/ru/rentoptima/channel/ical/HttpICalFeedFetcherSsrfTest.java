package ru.rentoptima.channel.ical;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.channel.ChannelSyncException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HttpICalFeedFetcher: SSRF guard integration")
class HttpICalFeedFetcherSsrfTest {

    private HttpServer server;
    private int port;
    private HttpICalFeedFetcher fetcher;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/feed.ics", ex -> {
            byte[] body = ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/calendar");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        fetcher = new HttpICalFeedFetcher();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("отказывается коннектиться на 127.0.0.1 даже если ответ был бы 200 OK")
    void refusesLoopback() {
        String url = "http://127.0.0.1:" + port + "/feed.ics";
        assertThatThrownBy(() -> fetcher.fetch(url))
                .isInstanceOf(ChannelSyncException.class);
    }

    @Test
    @DisplayName("отказывается на URL с портом postgres/staging")
    void refusesForbiddenPort() {
        assertThatThrownBy(() -> fetcher.fetch("http://example.com:5432/"))
                .isInstanceOf(ChannelSyncException.class);
        assertThatThrownBy(() -> fetcher.fetch("http://example.com:8081/"))
                .isInstanceOf(ChannelSyncException.class);
    }

    @Test
    @DisplayName("отказывается на webcal://127.0.0.1")
    void refusesWebcalLoopback() {
        assertThatThrownBy(() -> fetcher.fetch("webcal://127.0.0.1/feed.ics"))
                .isInstanceOf(ChannelSyncException.class);
    }

    @Test
    @DisplayName("отказывается на пустой URL")
    void refusesEmpty() {
        assertThatThrownBy(() -> fetcher.fetch(""))
                .isInstanceOf(ChannelSyncException.class);
        assertThatThrownBy(() -> fetcher.fetch(null))
                .isInstanceOf(ChannelSyncException.class);
    }
}
