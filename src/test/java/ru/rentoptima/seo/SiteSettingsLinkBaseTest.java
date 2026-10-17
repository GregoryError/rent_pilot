package ru.rentoptima.seo;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** Адрес для ссылок в письмах: подменённый Host не должен попадать в ссылку сброса пароля. */
class SiteSettingsLinkBaseTest {

    private static SiteSettings site(String environment) {
        return new SiteSettings(environment, "https://optirent.ru/", "", "", "", "");
    }

    private static MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/forgot");
        r.setScheme(scheme);
        r.setServerName(host);
        r.setServerPort(port);
        return r;
    }

    @Test
    void stagingLinksPointToStaging() {
        assertThat(site("staging").linkBase(request("https", "staging.optirent.ru", 443)))
                .isEqualTo("https://staging.optirent.ru");
        assertThat(site("dev").linkBase(request("http", "localhost", 8080))).isEqualTo("http://localhost:8080");
    }

    @Test
    void foreignHostFallsBackToOwnDomain() {
        assertThat(site("staging").linkBase(request("https", "evil.example", 443))).isEqualTo("https://optirent.ru");
        assertThat(site("staging").linkBase(request("https", "optirent.ru.evil.example", 443)))
                .isEqualTo("https://optirent.ru");
    }

    @Test
    void prodAlwaysUsesConfiguredDomain() {
        assertThat(site("prod").linkBase(request("https", "www.optirent.ru", 443))).isEqualTo("https://optirent.ru");
        assertThat(site("prod").linkBase(request("https", "evil.example", 443))).isEqualTo("https://optirent.ru");
    }

    @Test
    void canonicalUsesSameBaseAsLinks() {
        assertThat(site("prod").canonical(request("https", "staging.optirent.ru", 443), "/b/x"))
                .isEqualTo("https://optirent.ru/b/x");
        assertThat(site("staging").canonical(request("https", "staging.optirent.ru", 443), "/b/x"))
                .isEqualTo("https://staging.optirent.ru/b/x");
    }

    @Test
    void secondaryHostOnlyOnProdAndOnlyForOtherDomain() {
        assertThat(site("prod").isSecondaryHost(request("https", "staging.optirent.ru", 443))).isTrue();
        assertThat(site("prod").isSecondaryHost(request("https", "OptiRent.ru", 443))).isFalse();
        assertThat(site("staging").isSecondaryHost(request("https", "staging.optirent.ru", 443))).isFalse();
    }
}
