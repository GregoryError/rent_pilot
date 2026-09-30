package ru.rentoptima.channel.ical;

import lombok.extern.slf4j.Slf4j;
import ru.rentoptima.channel.ChannelSyncException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Проверка URL перед сетевым запросом — защита от SSRF.
 * <p>
 * Работает в два шага:
 * <ol>
 *   <li>{@link #normalizeAndCheckShape(String)} — валидирует схему, порт, распознаёт webcal;</li>
 *   <li>{@link #resolveAndCheck(String)} — резолвит хост в IP, проверяет КАЖДЫЙ полученный
 *       адрес против чёрного списка и возвращает первый безопасный.</li>
 * </ol>
 * {@link HttpICalFeedFetcher} выключает автоматические редиректы и вручную прогоняет
 * каждый Location через тот же guard.
 */
@Slf4j
public final class UrlSafetyGuard {

    private static final List<String> ALLOWED_SCHEMES = List.of("http", "https");
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;

    private static final List<Cidr4> DENY_V4 = List.of(
            Cidr4.parse("0.0.0.0/8"),
            Cidr4.parse("10.0.0.0/8"),
            Cidr4.parse("100.64.0.0/10"),
            Cidr4.parse("127.0.0.0/8"),
            Cidr4.parse("169.254.0.0/16"),
            Cidr4.parse("172.16.0.0/12"),
            Cidr4.parse("192.0.0.0/24"),
            Cidr4.parse("192.0.2.0/24"),
            Cidr4.parse("192.168.0.0/16"),
            Cidr4.parse("198.18.0.0/15"),
            Cidr4.parse("198.51.100.0/24"),
            Cidr4.parse("203.0.113.0/24"),
            Cidr4.parse("224.0.0.0/4"),
            Cidr4.parse("240.0.0.0/4")
    );

    private UrlSafetyGuard() {}

    public static URI normalizeAndCheckShape(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ChannelSyncException("URL импорта не задан");
        }
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new ChannelSyncException("Некорректный URL: " + trimmed);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("webcal")) {
            try {
                uri = URI.create("https://" + trimmed.substring("webcal://".length()));
            } catch (IllegalArgumentException e) {
                throw new ChannelSyncException("Некорректный webcal URL: " + trimmed);
            }
            scheme = "https";
        }
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new ChannelSyncException("Схема URL не поддерживается: " + scheme);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ChannelSyncException("URL без хоста: " + trimmed);
        }
        int port = uri.getPort();
        if (port != -1 && port != HTTP_PORT && port != HTTPS_PORT) {
            throw new ChannelSyncException("Порт " + port + " не разрешён; ожидаются 80 или 443");
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new ChannelSyncException("URL с userinfo не поддерживается");
        }
        return uri;
    }

    public static InetAddress resolveAndCheck(String host) {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new ChannelSyncException("Не удалось разрешить хост: " + host);
        }
        if (addrs.length == 0) {
            throw new ChannelSyncException("Хост не разрешается ни в один адрес: " + host);
        }
        for (InetAddress addr : addrs) {
            if (isDenied(addr)) {
                throw new ChannelSyncException(
                        "Адрес " + addr.getHostAddress() + " хоста " + host + " запрещён");
            }
        }
        return addrs[0];
    }

    public static boolean isDenied(InetAddress addr) {
        if (addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()
                || addr.isAnyLocalAddress()) {
            return true;
        }
        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            for (Cidr4 c : DENY_V4) {
                if (c.contains(bytes)) return true;
            }
            return false;
        }
        if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
            return true;
        }
        return false;
    }

    private record Cidr4(byte[] prefix, int bits) {
        static Cidr4 parse(String cidr) {
            String[] parts = cidr.split("/");
            if (parts.length != 2) throw new IllegalArgumentException("Bad CIDR: " + cidr);
            String[] octets = parts[0].split("\\.");
            if (octets.length != 4) throw new IllegalArgumentException("Bad CIDR: " + cidr);
            byte[] p = new byte[4];
            for (int i = 0; i < 4; i++) p[i] = (byte) Integer.parseInt(octets[i]);
            int b = Integer.parseInt(parts[1]);
            if (b < 0 || b > 32) throw new IllegalArgumentException("Bad CIDR bits: " + cidr);
            return new Cidr4(p, b);
        }

        boolean contains(byte[] addr) {
            if (addr.length != 4) return false;
            int full = bits / 8;
            int rem = bits % 8;
            for (int i = 0; i < full; i++) {
                if (addr[i] != prefix[i]) return false;
            }
            if (rem == 0) return true;
            int mask = 0xFF00 >> rem;
            return ((addr[full] ^ prefix[full]) & mask) == 0;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof Cidr4 c && bits == c.bits && Arrays.equals(prefix, c.prefix));
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(prefix) * 31 + bits;
        }
    }
}
