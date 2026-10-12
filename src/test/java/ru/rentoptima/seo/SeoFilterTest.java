package ru.rentoptima.seo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Индексация: X-Robots-Tag, robots.txt и слэш на конце адреса")
class SeoFilterTest {

    private static SiteSettings site(String environment) {
        return new SiteSettings(environment, "https://optirent.ru/", "", "", "abcdef0123456789", "");
    }

    private static MockHttpServletResponse get(String environment, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SeoFilter(site(environment)).doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    @DisplayName("staging и запуск без переменной закрыты целиком, включая статику и страницы бронирования")
    void nonProductionIsClosedEverywhere() throws Exception {
        for (String env : new String[]{"staging", "dev", "", "partner"}) {
            for (String path : new String[]{"/", "/b/loft", "/css/core.css", "/legal/privacy", "/login",
                    "/robots.txt", "/media/widget/a.jpg", "/api/widget/loft/config"}) {
                assertThat(get(env, path).getHeader(SeoFilter.HEADER)).as(env + " " + path)
                        .isEqualTo("noindex, nofollow");
            }
            assertThat(SeoController.robotsTxt(site(env))).contains("User-agent: *\nDisallow: /\n")
                    .doesNotContain("Sitemap");
        }
    }

    @Test
    @DisplayName("prod: открыты главная, правовые, /b/<slug> и файлы; всё остальное закрыто")
    void productionOpensOnlyPublicPages() throws Exception {
        for (String path : new String[]{"/", "/b/loft", "/legal/privacy", "/css/core.css", "/w.js",
                "/media/widget/a-960.webp", "/robots.txt", "/sitemap.xml", "/favicon.ico"}) {
            assertThat(get("prod", path).getHeader(SeoFilter.HEADER)).as(path).isNull();
        }
        for (String path : new String[]{"/login", "/register", "/dashboard", "/calendar/grid", "/settings/widgets/1",
                "/b/loft/embed", "/api/widget/loft/config", "/q/abc1234", "/f/abc1234", "/feedback/abc",
                "/widget/secret", "/ical/secret.ics", "/housekeeper/abc", "/learning", "/no-such-page"}) {
            assertThat(get("prod", path).getHeader(SeoFilter.HEADER)).as(path).isEqualTo("noindex, nofollow");
        }
    }

    @Test
    @DisplayName("адрес со слэшем на конце — 301 на адрес без него, параметры сохраняются")
    void trailingSlashRedirects() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/b/loft/");
        request.setQueryString("checkin=2026-11-13");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SeoFilter(site("prod")).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(301);
        assertThat(response.getHeader("Location")).isEqualTo("/b/loft?checkin=2026-11-13");
        assertThat(get("prod", "/").getStatus()).isEqualTo(200);

        // отправку формы не перенаправляем: редирект потерял бы её тело
        MockHttpServletResponse post = new MockHttpServletResponse();
        new SeoFilter(site("prod")).doFilter(new MockHttpServletRequest("POST", "/login/"), post, new MockFilterChain());
        assertThat(post.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("robots.txt на prod: служебное закрыто, Clean-param и Sitemap на месте")
    void productionRobots() {
        String robots = SeoController.robotsTxt(site("prod"));

        assertThat(robots).contains("Disallow: /api/\n", "Disallow: /q/\n", "Disallow: /f/\n",
                "Disallow: /b/*/embed\n", "Disallow: /widget/\n", "Disallow: /feedback\n", "Disallow: /ical/\n",
                "Disallow: /login\n", "Disallow: /register\n", "Disallow: /settings\n");
        assertThat(robots).doesNotContain("Disallow: /\n", "Disallow: /b/\n", "Disallow: /media", "Disallow: /legal");
        assertThat(robots).contains("Clean-param: utm_source&utm_medium&utm_campaign&utm_content&utm_term"
                + "&promo&checkin&checkout&guests&adults&children&pets&lang&yclid&gclid\n");
        assertThat(robots).endsWith("Sitemap: https://optirent.ru/sitemap.xml\n");
        // Яндекс читает не больше 500 символов в строке Clean-param
        assertThat(robots.lines().filter(l -> l.startsWith("Clean-param")).findFirst().orElseThrow().length())
                .isLessThan(500);
    }

    @Test
    @DisplayName("ключ IndexNow: только 8–128 символов из латиницы, цифр и дефиса")
    void indexNowKey() {
        assertThat(IndexNowService.validKey("abcdef0123456789")).isTrue();
        assertThat(IndexNowService.validKey("short")).isFalse();
        assertThat(IndexNowService.validKey("with space 123456")).isFalse();
        assertThat(IndexNowService.validKey("../etc/passwd")).isFalse();
        assertThat(IndexNowService.validKey("")).isFalse();
        assertThat(IndexNowService.validKey(null)).isFalse();
    }
}
