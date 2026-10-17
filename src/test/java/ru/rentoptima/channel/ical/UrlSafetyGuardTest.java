package ru.rentoptima.channel.ical;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.rentoptima.channel.ChannelSyncException;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UrlSafetyGuard: SSRF")
class UrlSafetyGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/feed.ics",
            "file:///etc/passwd",
            "gopher://example.com",
            "javascript:alert(1)",
            "jar:http://example.com!/x"
    })
    @DisplayName("отклоняет запрещённые схемы")
    void deniesBadSchemes(String url) {
        assertThatThrownBy(() -> UrlSafetyGuard.normalizeAndCheckShape(url))
                .isInstanceOf(ChannelSyncException.class);
    }

    @Test
    @DisplayName("webcal:// нормализуется в https://")
    void webcalNormalized() {
        URI uri = UrlSafetyGuard.normalizeAndCheckShape("webcal://example.com/feed.ics");
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com:22/", "https://example.com:5432/", "http://example.com:8080/"})
    @DisplayName("отклоняет непривычные порты")
    void deniesNonStandardPorts(String url) {
        assertThatThrownBy(() -> UrlSafetyGuard.normalizeAndCheckShape(url))
                .isInstanceOf(ChannelSyncException.class)
                .hasMessageContaining("Порт");
    }

    @Test
    @DisplayName("принимает 80 и 443 явно и без порта")
    void allowsStandardPorts() {
        assertThatCode(() -> UrlSafetyGuard.normalizeAndCheckShape("http://example.com:80/x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> UrlSafetyGuard.normalizeAndCheckShape("https://example.com:443/x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> UrlSafetyGuard.normalizeAndCheckShape("https://example.com/x"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("отклоняет URL с userinfo")
    void deniesUserinfo() {
        assertThatThrownBy(() -> UrlSafetyGuard.normalizeAndCheckShape("http://a:b@example.com/x"))
                .isInstanceOf(ChannelSyncException.class)
                .hasMessageContaining("userinfo");
    }

    @Test
    @DisplayName("отклоняет пустой URL и без хоста")
    void deniesEmptyOrHostless() {
        assertThatThrownBy(() -> UrlSafetyGuard.normalizeAndCheckShape(""))
                .isInstanceOf(ChannelSyncException.class);
        assertThatThrownBy(() -> UrlSafetyGuard.normalizeAndCheckShape("   "))
                .isInstanceOf(ChannelSyncException.class);
        assertThatThrownBy(() -> UrlSafetyGuard.normalizeAndCheckShape("http:///path"))
                .isInstanceOf(ChannelSyncException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.1.2.3",
            "10.0.0.1", "10.255.255.255",
            "192.168.0.1", "192.168.100.100",
            "172.16.0.1", "172.31.255.254",
            "169.254.169.254",
            "100.64.0.1",
            "0.0.0.0",
            "224.0.0.1",
            "::1",
            "fc00::1",
            "fd00::1",
            "fe80::1",
            "::"
    })
    @DisplayName("isDenied: помечает запрещённые адреса")
    void deniesPrivate(String ip) throws Exception {
        assertThat(UrlSafetyGuard.isDenied(InetAddress.getByName(ip)))
                .as("должен быть запрещён: %s", ip)
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.1.1.1",
            "8.8.8.8",
            "94.183.236.144",
            "2001:4860:4860::8888"
    })
    @DisplayName("isDenied: пропускает публичные адреса")
    void allowsPublic(String ip) throws Exception {
        assertThat(UrlSafetyGuard.isDenied(InetAddress.getByName(ip)))
                .as("должен быть разрешён: %s", ip)
                .isFalse();
    }

    @Test
    @DisplayName("resolveAndCheck: literal 127.0.0.1 запрещён")
    void resolveDeniesLoopbackLiteral() {
        assertThatThrownBy(() -> UrlSafetyGuard.resolveAndCheck("127.0.0.1"))
                .isInstanceOf(ChannelSyncException.class);
    }

    @Test
    @DisplayName("resolveAndCheck: literal 169.254.169.254 запрещён")
    void resolveDeniesMetadata() {
        assertThatThrownBy(() -> UrlSafetyGuard.resolveAndCheck("169.254.169.254"))
                .isInstanceOf(ChannelSyncException.class);
    }

    @Test
    @DisplayName("resolveAndCheck: несуществующий хост даёт понятную ошибку")
    void resolveFailsOnUnknown() {
        assertThatThrownBy(() -> UrlSafetyGuard.resolveAndCheck("nonexistent-host-abc.invalid"))
                .isInstanceOf(ChannelSyncException.class)
                .hasMessageContaining("разрешить");
    }
}
