package ru.rentoptima.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.channel.ChannelSyncException;
import ru.rentoptima.channel.ical.ICalFeedFetcher;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.security.TenantUserDetails;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Мастер первой настройки на настоящем PostgreSQL. Календари площадок подменены: ссылки на
 * cian.ru отдают календарь с двумя бронями, example.com — обычную страницу, остальное не
 * открывается. Нужен docker; без него тест пропускается.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.uploads.dir=target/test-uploads"})
@AutoConfigureMockMvc
@DisplayName("Мастер первой настройки: ветки, проверка ссылок, состояние")
class OnboardingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String CIAN_LINK = "https://www.cian.ru/calendar/ical/calendars/100200300_test.ics";
    private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;

    @TestConfiguration
    static class FakeFeeds {
        @Bean
        @Primary
        ICalFeedFetcher fakeFetcher() {
            DateTimeFormatter d = DateTimeFormatter.BASIC_ISO_DATE;
            LocalDate a = LocalDate.now().plusDays(5), b = LocalDate.now().plusDays(40);
            String calendar = "BEGIN:VCALENDAR\nVERSION:2.0\n"
                    + event("cian-1", a.format(d), a.plusDays(3).format(d))
                    + event("cian-2", b.format(d), b.plusDays(2).format(d))
                    + "END:VCALENDAR\n";
            return url -> {
                if (url.contains("cian.ru")) return calendar;
                if (url.contains("example.com")) return "<html><body>Войдите в кабинет</body></html>";
                throw new ChannelSyncException("HTTP 404");
            };
        }

        private static String event(String uid, String from, String to) {
            return "BEGIN:VEVENT\nUID:" + uid + "\nDTSTART;VALUE=DATE:" + from + "\nDTEND;VALUE=DATE:" + to
                    + "\nSUMMARY:Занято\nEND:VEVENT\n";
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired OnboardingService onboarding;
    @Autowired OnboardingStateRepository states;

    /** Новый пользователь, как после регистрации: со строкой состояния онбординга. */
    private TenantUserDetails newcomer() {
        TenantUserDetails u = account();
        onboarding.begin(u.getUserId(), u.getTenantId());
        return u;
    }

    private TenantUserDetails account() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Тест', ?) RETURNING id",
                Long.class, "onb-" + tag);
        Long userId = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, display_name, role)"
                + " VALUES (?, ?, 'x', 'Хозяйка', 'OWNER') RETURNING id", Long.class, tenantId, tag + "@example.com");
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        User user = new User();
        user.setId(userId);
        user.setTenant(tenant);
        user.setUsername(tag + "@example.com");
        user.setPasswordHash("x");
        user.setDisplayName("Хозяйка");
        user.setRole(User.Role.OWNER);
        return new TenantUserDetails(user);
    }

    private String page(TenantUserDetails u, String url) throws Exception {
        return mvc.perform(get(url).with(user(u))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(UTF8);
    }

    private Long channel(TenantUserDetails u, String platform) {
        return jdbc.queryForObject("SELECT id FROM channels WHERE tenant_id = ? AND active AND config_json->>'platform' = ?",
                Long.class, u.getTenantId(), platform);
    }

    /** Сообщение в карточке после отправки ссылки. */
    private String linkMessage(TenantUserDetails u, Long channelId, String url, String... extra) throws Exception {
        var request = post("/onboarding/links/" + channelId).with(user(u)).with(csrf()).param("url", url);
        for (int i = 0; i < extra.length; i += 2) request.param(extra[i], extra[i + 1]);
        var flash = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn().getFlashMap();
        return ((OnboardingController.LinkResult) flash.get("linkResult")).message();
    }

    @Test
    @DisplayName("регистрация ведёт в мастер; вход новичка — тоже; старый пользователь онбординг не получает")
    void whoGetsTheWizard() throws Exception {
        String email = "new-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mvc.perform(post("/register").with(csrf()).param("email", email).param("password", "Passw0rd-1")
                        .param("confirmPassword", "Passw0rd-1").param("tenantName", "Новая хозяйка")
                        .param("agreedToTerms", "on").param("agreedToConsent", "on"))
                .andExpect(redirectedUrl("/onboarding"));
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        assertThat(states.findById(userId)).get().extracting(OnboardingState::getWizardStep).isEqualTo("start");

        // Новичок с незаконченным мастером: вход ведёт в мастер, а не в пустую шахматку
        TenantUserDetails fresh = newcomer();
        mvc.perform(get("/calendar/grid").with(user(fresh))).andExpect(redirectedUrl("/onboarding"));
        mvc.perform(get("/dashboard").with(user(fresh))).andExpect(redirectedUrl("/onboarding"));
        // с остальных страниц его никто не уводит
        mvc.perform(get("/settings/channels").with(user(fresh))).andExpect(status().isOk());

        // Пользователь, созданный до онбординга (строки состояния нет): всё как раньше
        TenantUserDetails old = account();
        mvc.perform(get("/calendar/grid").with(user(old))).andExpect(status().isOk());
        mvc.perform(get("/onboarding").with(user(old))).andExpect(redirectedUrl("/calendar/grid"));
        mvc.perform(get("/welcome").with(user(old))).andExpect(redirectedUrl("/calendar/grid"));
        // …пока сам не попросит
        mvc.perform(post("/onboarding/restart").with(user(old)).with(csrf())).andExpect(redirectedUrl("/onboarding"));
        assertThat(page(old, "/onboarding")).contains("С чего начнём?");
    }

    @Test
    @DisplayName("ветка А: объект, площадки, ловушки со ссылками, брони в шахматке")
    void platformsBranch() throws Exception {
        TenantUserDetails u = newcomer();
        assertThat(page(u, "/onboarding")).contains("С чего начнём?", "Пропустить, настрою сам", "Шаг 1 из 4",
                "И то и другое");

        mvc.perform(post("/onboarding/branch").with(user(u)).with(csrf()).param("branch", "A"))
                .andExpect(redirectedUrl("/onboarding"));
        assertThat(page(u, "/onboarding")).contains("Расскажите о квартире", "Шаг 2 из 4");

        // Пустое название — остаёмся на шаге, введённое не теряется
        mvc.perform(post("/onboarding/property").with(user(u)).with(csrf()).param("name", " ").param("city", "Приморск")
                .param("basePrice", "4500")).andExpect(redirectedUrl("/onboarding?step=property"));
        mvc.perform(post("/onboarding/property").with(user(u)).with(csrf()).param("name", "Студия у парка")
                .param("city", "Приморск").param("unitCount", "1").param("basePrice", "4500"))
                .andExpect(redirectedUrl("/onboarding"));
        assertThat(jdbc.queryForObject("SELECT city FROM properties WHERE tenant_id = ?", String.class, u.getTenantId()))
                .isEqualTo("Приморск");
        assertThat(jdbc.queryForObject("SELECT base_price FROM unit_types WHERE tenant_id = ?", Integer.class,
                u.getTenantId())).isEqualTo(4500);

        String tiles = page(u, "/onboarding");
        assertThat(tiles).contains("Где вы сдаёте?", "Авито", "Циан", "Суточно", "Островок", "DomClick",
                "Яндекс Путешествия", "Другая");
        mvc.perform(post("/onboarding/platforms").with(user(u)).with(csrf()))
                .andExpect(redirectedUrl("/onboarding?step=platforms"));
        mvc.perform(post("/onboarding/platforms").with(user(u)).with(csrf()).param("platform", "avito", "cian"))
                .andExpect(redirectedUrl("/onboarding"));
        Long avito = channel(u, "avito"), cian = channel(u, "cian");
        // у площадки сразу есть наша ссылка и её цвет из файла
        String ourSecret = jdbc.queryForObject("SELECT export_secret FROM channels WHERE id = ?", String.class, avito);
        assertThat(ourSecret).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT color FROM channels WHERE id = ?", String.class, cian)).isEqualTo("#1D4ED8");

        String links = page(u, "/onboarding");
        assertThat(links).contains("Ссылка с площадки", "Ссылка для площадки", "/ical/" + ourSecret + ".ics",
                "Где её взять", "Куда её вставить", "обычно до получаса", "раз в 10–60 минут",
                "Циан может вернуть вашу же запись обратно как свою", "Подключу позже");
        // язык мастера: без технических слов (адреса ссылок не в счёт)
        String visible = links.replaceAll("(?s)<script.*?</script>", " ").replaceAll("(?s)<[^>]+>", " ")
                .replaceAll("https?://\\S+", " ").toLowerCase(Locale.ROOT);
        assertThat(visible).doesNotContain("ical", "импорт", "экспорт", "канал", "фид", "тенант");

        // 1. Наша ссылка не в том поле
        assertThat(linkMessage(u, avito, "http://localhost/ical/" + ourSecret + ".ics"))
                .startsWith("Это наша ссылка — она для площадки");
        assertThat(linkMessage(u, avito, "https://optirent.ru/ical/abc123.ics")).startsWith("Это наша ссылка");
        // 2. Не ссылка и не календарь
        assertThat(linkMessage(u, avito, "привет")).startsWith("Это не похоже на ссылку");
        assertThat(linkMessage(u, avito, "https://example.com/my-listing")).startsWith("По этой ссылке нет календаря");
        assertThat(linkMessage(u, avito, "https://avito.ru/calendar/broken.ics")).startsWith("Ссылка не открывается");
        // ничего из этого не сохранилось
        assertThat(jdbc.queryForObject("SELECT config_json->>'import_url' FROM channels WHERE id = ?", String.class, avito))
                .isNull();

        // 3. Ссылка Циана в карточке Авито: предупреждение, ссылка никуда не сохранена
        var flash = mvc.perform(post("/onboarding/links/" + avito).with(user(u)).with(csrf()).param("url", CIAN_LINK))
                .andExpect(redirectedUrl("/onboarding?step=links#card-" + avito)).andReturn().getFlashMap();
        OnboardingController.LinkResult warn = (OnboardingController.LinkResult) flash.get("linkResult");
        assertThat(warn.message()).isEqualTo("Похоже, это ссылка с площадки «Циан», а карточка — «Авито».");
        assertThat(warn.moveTo()).isEqualTo("cian");
        assertThat(warn.moveLabel()).isEqualTo("Перенести в карточку «Циан»");
        assertThat(warn.saved()).isFalse();

        // «Перенести в карточку Циан»: ссылка проверена и сохранена у Циана, брони подтянулись
        mvc.perform(post("/onboarding/links/" + avito).with(user(u)).with(csrf()).param("url", CIAN_LINK)
                .param("moveTo", "cian")).andExpect(redirectedUrl("/onboarding?step=links#card-" + cian));
        assertThat(jdbc.queryForObject("SELECT config_json->>'import_url' FROM channels WHERE id = ?", String.class, cian))
                .isEqualTo(CIAN_LINK);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calendar_blocks WHERE channel_id = ?", Integer.class, cian))
                .isEqualTo(2);
        // повторная вставка той же ссылки в свою карточку — понятный итог
        assertThat(linkMessage(u, cian, CIAN_LINK)).startsWith("Нашли 2 занятых периода до ").endsWith("Ссылка сохранена.");
        // «Оставить здесь» — человек знает, что делает
        assertThat(linkMessage(u, avito, CIAN_LINK, "keep", "true")).startsWith("Нашли 2 занятых периода");

        // Чужая площадка по своему адресу недоступна
        TenantUserDetails stranger = newcomer();
        mvc.perform(post("/onboarding/links/" + cian).with(user(stranger)).with(csrf()).param("url", CIAN_LINK))
                .andExpect(status().isNotFound());

        // Дальше — финальный экран и шахматка с бронями
        mvc.perform(post("/onboarding/links/continue").with(user(u)).with(csrf())).andExpect(redirectedUrl("/onboarding"));
        assertThat(page(u, "/onboarding")).contains("Готово", "Площадки забирают ссылку раз в 10–60 минут",
                "шахматку — календарь всех ваших броней").doesNotContain("Пропустить, настрою сам");
        mvc.perform(post("/onboarding/finish").with(user(u)).with(csrf())).andExpect(redirectedUrl("/calendar/grid"));
        assertThat(states.findById(u.getUserId()).orElseThrow().getWizardStatus()).isEqualTo("DONE");
        assertThat(page(u, "/calendar/grid")).contains("grid__cell--colored");
        // к площадкам можно вернуться и после мастера
        assertThat(page(u, "/onboarding?step=links")).contains("Подключено");
    }

    @Test
    @DisplayName("ветка Б: страница бронирования с фото и контактами, затем предложение подключить площадки")
    void directBranch() throws Exception {
        TenantUserDetails u = newcomer();
        mvc.perform(post("/onboarding/branch").with(user(u)).with(csrf()).param("branch", "B"));
        assertThat(page(u, "/onboarding")).contains("Шаг 2 из 3");
        mvc.perform(post("/onboarding/property").with(user(u)).with(csrf()).param("name", "Домик у озера")
                .param("city", "Приозёрск").param("basePrice", "6000"));
        assertThat(page(u, "/onboarding")).contains("Ваша страница бронирования", "value=\"Домик у озера\"", "value=\"6000\"");

        mvc.perform(multipart("/onboarding/page")
                        .file(new MockMultipartFile("files", "not-a-photo.txt", "text/plain", "привет".getBytes(UTF8)))
                        .with(user(u)).with(csrf()).param("title", "Домик у озера").param("price", "6500")
                        .param("contactPhone", "+7 900 000-00-00").param("contactTelegram", "@host"))
                .andExpect(redirectedUrl("/onboarding?step=page"));
        String slug = jdbc.queryForObject("SELECT slug FROM booking_widgets WHERE tenant_id = ?", String.class, u.getTenantId());
        assertThat(jdbc.queryForObject("SELECT contact_telegram FROM booking_widgets WHERE slug = ?", String.class, slug))
                .isEqualTo("@host");
        assertThat(jdbc.queryForObject("SELECT base_price FROM unit_types WHERE tenant_id = ?", Integer.class,
                u.getTenantId())).isEqualTo(6500);
        assertThat(page(u, "/onboarding")).contains("Ваша страница готова", "/b/" + slug, "Настроить вид",
                "Сдаёте ещё и на площадках?");
        mvc.perform(get("/b/" + slug)).andExpect(status().isOk());
        // повторная отправка формы вторую страницу не создаёт
        mvc.perform(multipart("/onboarding/page").with(user(u)).with(csrf()).param("title", "Ещё одна"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_widgets WHERE tenant_id = ?", Integer.class,
                u.getTenantId())).isEqualTo(1);

        // «Подключить площадки» — продолжение веткой А с шага площадок
        mvc.perform(post("/onboarding/page/platforms").with(user(u)).with(csrf())).andExpect(redirectedUrl("/onboarding"));
        assertThat(page(u, "/onboarding")).contains("Где вы сдаёте?");
        assertThat(states.findById(u.getUserId()).orElseThrow().getBranch()).isEqualTo("AB");
    }

    @Test
    @DisplayName("состояние: прогресс сохраняется, «Пропустить» выключает мастер, ?onboarding=reset начинает заново")
    void stateSurvives() throws Exception {
        TenantUserDetails u = newcomer();
        mvc.perform(post("/onboarding/branch").with(user(u)).with(csrf()).param("branch", "AB"));
        mvc.perform(post("/onboarding/property").with(user(u)).with(csrf()).param("name", "Квартира")
                .param("city", "Приморск").param("basePrice", "3000"));
        // «закрыл вкладку»: следующий заход продолжает с того же шага
        assertThat(states.findById(u.getUserId()).orElseThrow().getWizardStep()).isEqualTo("platforms");
        assertThat(page(u, "/onboarding")).contains("Где вы сдаёте?", "Шаг 3 из 5");
        // к шагу, для которого ещё нет данных, перескочить нельзя
        assertThat(page(u, "/onboarding?step=links")).contains("Где вы сдаёте?");
        assertThat(page(u, "/onboarding?step=property")).contains("У вас уже есть объект «Квартира»");

        mvc.perform(post("/onboarding/skip").with(user(u)).with(csrf())).andExpect(redirectedUrl("/calendar/grid"));
        assertThat(states.findById(u.getUserId()).orElseThrow().getWizardStatus()).isEqualTo("SKIPPED");
        mvc.perform(get("/calendar/grid").with(user(u))).andExpect(status().isOk());

        // сброс — только своего состояния; созданный объект остаётся
        TenantUserDetails other = newcomer();
        mvc.perform(post("/onboarding/branch").with(user(other)).with(csrf()).param("branch", "B"));
        mvc.perform(get("/calendar/grid").param("onboarding", "reset").with(user(u))).andExpect(redirectedUrl("/onboarding"));
        assertThat(states.findById(u.getUserId()).orElseThrow().getWizardStep()).isEqualTo("start");
        assertThat(states.findById(other.getUserId()).orElseThrow().getBranch()).isEqualTo("B");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM properties WHERE tenant_id = ?", Integer.class,
                u.getTenantId())).isEqualTo(1);
        mvc.perform(post("/onboarding/branch").with(user(u)).with(csrf()).param("branch", "X"))
                .andExpect(status().isBadRequest());
        // без входа мастер недоступен
        mvc.perform(get("/onboarding")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/login")));
    }

    @Test
    @DisplayName("меню: раздел называется «Площадки», адрес прежний")
    void renamedSection() throws Exception {
        TenantUserDetails u = account();
        assertThat(page(u, "/settings/channels")).contains("<h1 class=\"page-title\">Площадки</h1>", "Площадки — OptiRent")
                .contains("href=\"/settings/channels\"");
    }
}
