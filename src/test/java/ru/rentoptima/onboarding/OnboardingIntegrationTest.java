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
    @Autowired OnboardingNotifier notifier;
    @Autowired OnboardingStats stats;

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

    @Test
    @DisplayName("тур: стартует после мастера, закрытый не возвращается, «Знакомство» запускает заново")
    void tour() throws Exception {
        TenantUserDetails u = newcomer();
        mvc.perform(post("/onboarding/branch").with(user(u)).with(csrf()).param("branch", "B"));
        mvc.perform(post("/onboarding/property").with(user(u)).with(csrf()).param("name", "Квартира")
                .param("city", "Приморск").param("basePrice", "3000"));
        // пока идёт мастер, тура и его скрипта на страницах нет
        assertThat(page(u, "/settings/channels")).doesNotContain("onboarding-config", "/js/onboarding");

        mvc.perform(post("/onboarding/finish").with(user(u)).with(csrf())).andExpect(redirectedUrl("/calendar/grid"));
        String grid = page(u, "/calendar/grid");
        assertThat(grid).contains("id=\"onboarding-config\"", "\"start\":0", "Это шахматка — календарь всех ваших броней",
                "data-tour=\"platforms\"", "data-tour=\"page\"", "data-tour=\"help\"", "Знакомство", "Инструкции")
                .containsPattern("/js/onboarding(-[0-9a-f]+)?\\.js").containsPattern("/css/onboarding(-[0-9a-f]+)?\\.css");

        // показан третий кадр, вкладку закрыли — в следующий раз тур продолжится с него
        mvc.perform(post("/onboarding/tour").with(user(u)).with(csrf()).param("event", "frame").param("frame", "2"))
                .andExpect(status().isNoContent());
        assertThat(page(u, "/calendar/grid")).contains("\"start\":2");

        // закрыл тур на кадре 2 — после перезагрузки тура нет (остаются чек-лист и подсказки)
        mvc.perform(post("/onboarding/tour").with(user(u)).with(csrf()).param("event", "close").param("frame", "1"))
                .andExpect(status().isNoContent());
        assertThat(page(u, "/calendar/grid")).doesNotContain("\"tour\"", "Это шахматка — календарь всех ваших броней");
        // опоздавшее событие кадра закрытый тур не оживляет
        mvc.perform(post("/onboarding/tour").with(user(u)).with(csrf()).param("event", "frame").param("frame", "3"));
        assertThat(page(u, "/calendar/grid")).doesNotContain("\"tour\"");
        // там, где показывать нечего, скрипт, стили и шрифт онбординга не загружаются вовсе
        mvc.perform(post("/onboarding/steps").with(user(u)).with(csrf()).param("action", "hide"));
        assertThat(page(u, "/pricing")).doesNotContain("onboarding-config", "/js/onboarding", "/css/onboarding");

        // «Знакомство» в меню — тур с первого кадра
        mvc.perform(post("/onboarding/tour/start").with(user(u)).with(csrf())).andExpect(redirectedUrl("/calendar/grid"));
        assertThat(page(u, "/calendar/grid")).contains("\"start\":0");
        mvc.perform(post("/onboarding/tour").with(user(u)).with(csrf()).param("event", "done").param("frame", "4"));
        assertThat(page(u, "/calendar/grid")).doesNotContain("\"tour\"");

        // Старый пользователь: тура нет, пока не нажмёт «Знакомство»; мастер ему при этом не открывается
        TenantUserDetails old = account();
        assertThat(page(old, "/calendar/grid")).doesNotContain("onboarding-config").contains("Знакомство");
        mvc.perform(post("/onboarding/tour/start").with(user(old)).with(csrf())).andExpect(redirectedUrl("/calendar/grid"));
        assertThat(page(old, "/calendar/grid")).contains("onboarding-config");
        assertThat(states.findById(old.getUserId()).orElseThrow().wizardActive()).isFalse();

        // событие без состояния и без входа ничего не ломает
        mvc.perform(post("/onboarding/tour").with(user(account())).with(csrf()).param("event", "close"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/onboarding/tour").with(csrf()).param("event", "close")).andExpect(status().is3xxRedirection());
    }

    private void wizardDone(TenantUserDetails u) throws Exception {
        mvc.perform(post("/onboarding/branch").with(user(u)).with(csrf()).param("branch", "A"));
        mvc.perform(post("/onboarding/property").with(user(u)).with(csrf()).param("name", "Студия у парка")
                .param("city", "Приморск").param("basePrice", "4500"));
        mvc.perform(post("/onboarding/platforms").with(user(u)).with(csrf()).param("platform", "cian"));
        mvc.perform(post("/onboarding/finish").with(user(u)).with(csrf()));
        // тур пройден — дальше подсказки и чек-лист
        mvc.perform(post("/onboarding/tour").with(user(u)).with(csrf()).param("event", "done"));
    }

    @Test
    @DisplayName("чек-лист: пункты отмечаются сами, сворачивается и скрывается, после всех шагов — поздравление и исчезает")
    void firstSteps() throws Exception {
        TenantUserDetails u = newcomer();
        // пока идёт мастер, чек-листа нет
        assertThat(page(u, "/settings/channels")).doesNotContain("id=\"first-steps\"", "steps-badge");
        wizardDone(u);

        String grid = page(u, "/calendar/grid");
        assertThat(grid).contains("id=\"first-steps\"", "Первые шаги", "1 из 6", "Добавить объект",
                "Подключить первую площадку", "Площадка забрала вашу ссылку", "Увидеть первую бронь в шахматке",
                "Создать страницу бронирования", "Распечатать карточку для гостей",
                "Отметится само, когда площадка в первый раз заберёт ссылку.", "href=\"/onboarding?step=platforms\"")
                .containsPattern("/css/onboarding(-[0-9a-f]+)?\\.css");
        assertThat(grid.split("onb-steps__item is-done", -1).length - 1).isEqualTo(1);
        // число оставшихся — в шапке и под логотипом; чек-лист есть и на главной
        assertThat(grid.split("class=\"steps-badge", -1).length - 1).isEqualTo(2);
        assertThat(grid).contains("Первые шаги <b>5</b>");
        assertThat(page(u, "/dashboard")).contains("id=\"first-steps\"");
        assertThat(page(u, "/pricing")).doesNotContain("id=\"first-steps\"").contains("steps-badge");

        // площадка подключена, брони пришли, шахматку открыл
        Long cian = channel(u, "cian");
        mvc.perform(post("/onboarding/links/" + cian).with(user(u)).with(csrf()).param("url", CIAN_LINK));
        assertThat(page(u, "/calendar/grid")).contains("3 из 6");
        // площадка забрала нашу ссылку: один заход из браузера — это, скорее всего, сам хозяин; не считается
        String secret = jdbc.queryForObject("SELECT export_secret FROM channels WHERE id = ?", String.class, cian);
        mvc.perform(get("/ical/" + secret + ".ics").header("User-Agent", "Mozilla/5.0 (Macintosh) Chrome/140 Safari/537"))
                .andExpect(status().isOk());
        assertThat(page(u, "/calendar/grid")).contains("3 из 6");
        mvc.perform(get("/ical/" + secret + ".ics").header("User-Agent", "CianCalendar/1.0")).andExpect(status().isOk());
        assertThat(page(u, "/calendar/grid")).contains("4 из 6");

        // свернул — запомнилось; из скрипта ответ без перезагрузки
        mvc.perform(post("/onboarding/steps").with(user(u)).with(csrf()).param("action", "collapse")
                .header("X-Requested-With", "fetch")).andExpect(status().isNoContent());
        assertThat(page(u, "/calendar/grid")).contains("class=\"onb-steps is-collapsed\"", "aria-expanded=\"false\"");
        mvc.perform(post("/onboarding/steps").with(user(u)).with(csrf()).param("action", "expand"))
                .andExpect(redirectedUrl("/calendar/grid"));

        // из чек-листа — к шагу мастера: без шкалы и «Пропустить», с выходом в кабинет
        String step = page(u, "/onboarding?step=page");
        assertThat(step).contains("Ваша страница бронирования", "Вернуться в кабинет")
                .doesNotContain("Пропустить, настрою сам", "wz-progress__bar");
        mvc.perform(multipart("/onboarding/page").with(user(u)).with(csrf()).param("title", "Студия у парка"));
        Long widgetId = jdbc.queryForObject("SELECT id FROM booking_widgets WHERE tenant_id = ?", Long.class, u.getTenantId());
        mvc.perform(post("/onboarding/page/continue").with(user(u)).with(csrf())).andExpect(redirectedUrl("/calendar/grid"));
        assertThat(states.findById(u.getUserId()).orElseThrow().getWizardStatus()).isEqualTo("DONE");
        assertThat(page(u, "/calendar/grid")).contains("5 из 6", "href=\"/settings/widgets/" + widgetId + "/card\"");

        // лист печати карточки открыт — все шаги пройдены: поздравление один раз, потом блока нет
        mvc.perform(get("/settings/widgets/" + widgetId + "/card/print").with(user(u))).andExpect(status().isOk());
        String done = page(u, "/calendar/grid");
        assertThat(done).contains("data-celebrate=\"true\"", "Всё готово!", "onb-confetti").doesNotContain("steps-badge", "onb-steps__list");
        mvc.perform(post("/onboarding/steps").with(user(u)).with(csrf()).param("action", "celebrated")
                .header("X-Requested-With", "fetch")).andExpect(status().isNoContent());
        assertThat(page(u, "/calendar/grid")).doesNotContain("id=\"first-steps\"", "steps-badge");

        // «Скрыть» — блока нет, хотя шаги не пройдены
        TenantUserDetails other = newcomer();
        wizardDone(other);
        mvc.perform(post("/onboarding/steps").with(user(other)).with(csrf()).param("action", "hide"));
        assertThat(page(other, "/calendar/grid")).doesNotContain("id=\"first-steps\"", "steps-badge");
        // старому пользователю чек-лист не показывается и после «Знакомства»
        TenantUserDetails old = account();
        mvc.perform(post("/onboarding/tour/start").with(user(old)).with(csrf()));
        mvc.perform(post("/onboarding/tour").with(user(old)).with(csrf()).param("event", "done"));
        assertThat(page(old, "/calendar/grid")).doesNotContain("id=\"first-steps\"", "steps-badge", "onboarding-config");
    }

    @Test
    @DisplayName("подсказки: не при туре и не старым пользователям, каждая один раз, не больше двух за день")
    void hints() throws Exception {
        TenantUserDetails u = newcomer();
        mvc.perform(post("/onboarding/branch").with(user(u)).with(csrf()).param("branch", "A"));
        mvc.perform(post("/onboarding/property").with(user(u)).with(csrf()).param("name", "Студия").param("city", "Приморск")
                .param("basePrice", "4500"));
        mvc.perform(post("/onboarding/platforms").with(user(u)).with(csrf()).param("platform", "cian"));
        mvc.perform(post("/onboarding/finish").with(user(u)).with(csrf()));
        // пока ждёт тур — подсказок в настройках страницы нет
        assertThat(page(u, "/calendar/grid")).contains("\"tour\"").doesNotContain("\"hints\"");
        mvc.perform(post("/onboarding/tour").with(user(u)).with(csrf()).param("event", "close"));

        String grid = page(u, "/calendar/grid");
        assertThat(grid).contains("\"hints\":[", "\"id\":\"first-booking\"", "\"id\":\"manual-delete\"", "\"watch\":true")
                .doesNotContain("\"tour\"", "not-fetched");
        // площадка без единого обращения помечена — на неё укажет подсказка
        assertThat(page(u, "/settings/channels")).contains("data-hint=\"not-fetched\"", "Площадка ещё не забирала ссылку",
                "\"id\":\"not-fetched\"");

        // показана — больше не приходит
        mvc.perform(post("/onboarding/hint").with(user(u)).with(csrf()).param("id", "first-booking"))
                .andExpect(status().isNoContent());
        assertThat(page(u, "/calendar/grid")).doesNotContain("first-booking").contains("manual-delete");
        // вторая за день — и на сегодня всё, на любой странице
        mvc.perform(post("/onboarding/hint").with(user(u)).with(csrf()).param("id", "not-fetched"));
        assertThat(page(u, "/calendar/grid")).doesNotContain("onboarding-config");
        // третья сверх лимита и несуществующая не записываются
        mvc.perform(post("/onboarding/hint").with(user(u)).with(csrf()).param("id", "manual-delete"));
        mvc.perform(post("/onboarding/hint").with(user(u)).with(csrf()).param("id", "no-such-hint"));
        assertThat(states.findById(u.getUserId()).orElseThrow().getData().path("hints").path("shown").size()).isEqualTo(2);

        // старый пользователь подсказок не получает, даже нажав «Знакомство»
        TenantUserDetails old = account();
        assertThat(page(old, "/calendar/grid")).doesNotContain("onboarding-config");
        mvc.perform(post("/onboarding/tour/start").with(user(old)).with(csrf()));
        mvc.perform(post("/onboarding/tour").with(user(old)).with(csrf()).param("event", "close"));
        assertThat(page(old, "/calendar/grid")).doesNotContain("onboarding-config", "\"hints\"");
        assertThat(page(old, "/settings/channels")).doesNotContain("onboarding-config");
    }

    @Test
    @DisplayName("пустые страницы: что здесь будет и одна кнопка; старому пользователю кнопка тоже открывает шаг")
    void emptyStates() throws Exception {
        TenantUserDetails old = account();
        assertThat(page(old, "/calendar/grid")).contains("empty-state", "Здесь будет шахматка",
                "Шахматка — календарь всех ваших броней", "href=\"/onboarding?step=property\"");
        assertThat(page(old, "/settings/channels")).contains("Площадок пока нет", "href=\"/onboarding?step=platforms\"")
                .doesNotContain("Сначала добавьте категорию номеров");
        assertThat(page(old, "/bookings/pending")).contains("Заявок пока нет", "href=\"/settings/widgets\"");
        assertThat(page(old, "/expenses")).contains("Расходов за этот месяц нет");
        // кнопка с пустой страницы ведёт к шагу мастера — он открывается и тому, кто создан до онбординга,
        // но мастер, чек-лист и подсказки после этого ему не навязываются
        assertThat(page(old, "/onboarding?step=platforms")).contains("Расскажите о квартире", "Вернуться в кабинет");
        mvc.perform(get("/calendar/grid").with(user(old))).andExpect(status().isOk());
        assertThat(page(old, "/calendar/grid")).doesNotContain("id=\"first-steps\"", "onboarding-config");
        mvc.perform(get("/onboarding").with(user(account()))).andExpect(redirectedUrl("/calendar/grid"));
    }

    private long hits(String event) {
        return stats.totals(LocalDate.now(), LocalDate.now()).get(event);
    }

    @Test
    @DisplayName("сопровождение: площадка забрала ссылку и пришли брони — плашка один раз; через сутки — напоминание")
    void notices() throws Exception {
        TenantUserDetails u = newcomer();
        wizardDone(u);
        Long cian = channel(u, "cian");
        notifier.check();
        assertThat(page(u, "/calendar/grid")).doesNotContain("class=\"notice\"");

        // пришли брони с площадки
        mvc.perform(post("/onboarding/links/" + cian).with(user(u)).with(csrf()).param("url", CIAN_LINK));
        assertThat(notifier.check()).isGreaterThanOrEqualTo(1);
        String grid = page(u, "/calendar/grid");
        assertThat(grid).contains("class=\"notice\"", "С площадки «Циан» пришли первые брони — они уже в шахматке.");
        // показана один раз; повторный проход второй раз не создаёт
        assertThat(page(u, "/calendar/grid")).doesNotContain("class=\"notice\"");
        notifier.check();
        assertThat(page(u, "/calendar/grid")).doesNotContain("class=\"notice\"");

        // площадка впервые забрала нашу ссылку
        String secret = jdbc.queryForObject("SELECT export_secret FROM channels WHERE id = ?", String.class, cian);
        mvc.perform(get("/ical/" + secret + ".ics").header("User-Agent", "CianCalendar/1.0"));
        notifier.check();
        assertThat(page(u, "/settings/channels")).contains("«Циан» подключилась: площадка забрала вашу ссылку и видит ваш календарь.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM onboarding_notices WHERE channel_id = ?", Integer.class, cian))
                .isEqualTo(2);
        // чужой хозяин чужих плашек не видит
        TenantUserDetails stranger = newcomer();
        wizardDone(stranger);
        assertThat(page(stranger, "/calendar/grid")).doesNotContain("class=\"notice\"");

        // у созданного до онбординга сопровождения нет: событие есть, сообщения нет
        TenantUserDetails old = account();
        Long propertyId = jdbc.queryForObject("INSERT INTO properties(tenant_id, name, feedback_code, housekeeper_code)"
                + " VALUES (?, 'Старая', ?, ?) RETURNING id", Long.class, old.getTenantId(), "f" + old.getUserId(), "h" + old.getUserId());
        Long unitId = jdbc.queryForObject("INSERT INTO unit_types(tenant_id, property_id, name, unit_count)"
                + " VALUES (?, ?, 'Основной', 1) RETURNING id", Long.class, old.getTenantId(), propertyId);
        Long oldChannel = jdbc.queryForObject("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled, export_secret)"
                + " VALUES (?, ?, 'ICAL', 'Авито', false, ?) RETURNING id", Long.class, old.getTenantId(), unitId, "old-" + old.getUserId());
        jdbc.update("INSERT INTO channel_feed_fetches(tenant_id, channel_id, user_agent) VALUES (?, ?, 'AvitoBot/1.0')",
                old.getTenantId(), oldChannel);
        notifier.check();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM onboarding_notices WHERE channel_id = ?", Integer.class, oldChannel))
                .isZero();

        // напоминание: площадка заведена больше суток назад и ни разу не забрала ссылку
        TenantUserDetails late = newcomer();
        wizardDone(late);
        assertThat(page(late, "/calendar/grid")).contains("Отметится само, когда площадка в первый раз заберёт ссылку.")
                .doesNotContain("ссылку пока не забирали");
        jdbc.update("UPDATE channels SET created_at = NOW() - INTERVAL '25 hours' WHERE tenant_id = ?", late.getTenantId());
        assertThat(page(late, "/calendar/grid")).contains("«Циан»: ссылку пока не забирали. Проверьте, что вставили её в кабинете площадки.",
                "href=\"/instrukcii/cian\"", "Открыть инструкцию", "onb-steps__item is-late");
    }

    @Test
    @DisplayName("воронка: события считаются без пользователей; страница — только администратору")
    void funnel() throws Exception {
        long start = hits("wizard_start"), branch = hits("branch_A"), property = hits("step_property"),
                done = hits("wizard_done"), skip = hits("wizard_skip"), frame2 = hits("tour_frame_2"),
                close = hits("tour_close"), checkProperty = hits("check_property");

        TenantUserDetails u = newcomer();
        mvc.perform(post("/onboarding/branch").with(user(u)).with(csrf()).param("branch", "A"));
        mvc.perform(post("/onboarding/property").with(user(u)).with(csrf()).param("name", "Студия").param("city", "Приморск")
                .param("basePrice", "4500"));
        mvc.perform(post("/onboarding/finish").with(user(u)).with(csrf()));
        // кадр считается один раз, даже если браузер прислал его дважды; закрытие — один раз
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/onboarding/tour").with(user(u)).with(csrf()).param("event", "frame").param("frame", "1"));
        }
        mvc.perform(post("/onboarding/tour").with(user(u)).with(csrf()).param("event", "close").param("frame", "1"));
        mvc.perform(post("/onboarding/tour").with(user(u)).with(csrf()).param("event", "close").param("frame", "1"));
        // пункт чек-листа — один раз на пользователя, сколько бы страниц он ни открыл
        page(u, "/calendar/grid");
        page(u, "/dashboard");
        TenantUserDetails quitter = newcomer();
        mvc.perform(post("/onboarding/skip").with(user(quitter)).with(csrf()));

        assertThat(hits("wizard_start")).isEqualTo(start + 2);
        assertThat(hits("branch_A")).isEqualTo(branch + 1);
        assertThat(hits("step_property")).isEqualTo(property + 1);
        assertThat(hits("wizard_done")).isEqualTo(done + 1);
        assertThat(hits("wizard_skip")).isEqualTo(skip + 1);
        assertThat(hits("tour_frame_2")).isEqualTo(frame2 + 1);
        assertThat(hits("tour_close")).isEqualTo(close + 1);
        assertThat(hits("check_property")).isEqualTo(checkProperty + 1);
        // в таблице только день, событие и число
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'onboarding_funnel_daily'"
                + " ORDER BY ordinal_position", String.class)).containsExactly("day", "event", "hits");

        // страница — только администратору системы
        mvc.perform(get("/admin/onboarding").with(user(u))).andExpect(status().isForbidden());
        Tenant tenant = new Tenant();
        tenant.setId(u.getTenantId());
        User admin = new User();
        admin.setId(u.getUserId());
        admin.setTenant(tenant);
        admin.setUsername("admin");
        admin.setPasswordHash("x");
        admin.setDisplayName("Администратор");
        admin.setRole(User.Role.ADMIN);
        String html = mvc.perform(get("/admin/onboarding").with(user(new TenantUserDetails(admin)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(UTF8);
        assertThat(html).contains("Воронка онбординга", "Мастер открыт (регистрация)", "Мастер пройден", "Мастер пропущен",
                "Кадр 2 — площадки", "Все шаги пройдены", "По дням", "href=\"/admin/onboarding?days=7\"");
        // ни одного адреса почты и названия объекта: только агрегаты
        assertThat(html).doesNotContain("@example.com", "Студия");
        mvc.perform(get("/admin/onboarding").param("days", "90").with(user(new TenantUserDetails(admin)))).andExpect(status().isOk());
    }

    /** Сообщение в карточке на странице «Площадки» после отправки ссылки. */
    private ru.rentoptima.controller.ChannelController.LinkResult channelLink(TenantUserDetails u, Long channelId,
                                                                              String url, String... extra) throws Exception {
        var request = post("/settings/channels/" + channelId + "/import-url").with(user(u)).with(csrf()).param("url", url);
        for (int i = 0; i < extra.length; i += 2) request.param(extra[i], extra[i + 1]);
        var flash = mvc.perform(request).andExpect(redirectedUrl("/settings/channels#channel-" + channelId))
                .andReturn().getFlashMap();
        return (ru.rentoptima.controller.ChannelController.LinkResult) flash.get("linkResult");
    }

    @Test
    @DisplayName("площадка из мастера без ссылки: ссылку можно вставить на странице «Площадки», с теми же проверками")
    void linkOnPlatformsPage() throws Exception {
        TenantUserDetails u = newcomer();
        mvc.perform(post("/onboarding/branch").with(user(u)).with(csrf()).param("branch", "A"));
        mvc.perform(post("/onboarding/property").with(user(u)).with(csrf()).param("name", "Студия").param("city", "Приморск")
                .param("basePrice", "4500"));
        mvc.perform(post("/onboarding/platforms").with(user(u)).with(csrf()).param("platform", "avito", "cian"));
        // начал подключать — и пропустил мастер
        mvc.perform(post("/onboarding/skip").with(user(u)).with(csrf()));
        Long avito = channel(u, "avito"), cian = channel(u, "cian");

        String html = page(u, "/settings/channels");
        assertThat(html).contains("id=\"import-" + avito + "\"", "id=\"import-" + cian + "\"",
                        "/settings/channels/" + cian + "/import-url", "Проверить и сохранить",
                        "href=\"/instrukcii/cian\"", "href=\"/instrukcii/avito\"", "Где её взять");

        // те же ловушки, что в мастере
        assertThat(channelLink(u, avito, "https://optirent.ru/ical/abc123.ics").message()).startsWith("Это наша ссылка");
        assertThat(channelLink(u, avito, "привет").message()).startsWith("Это не похоже на ссылку");
        assertThat(channelLink(u, avito, "https://example.com/my-listing").message()).startsWith("По этой ссылке нет календаря");
        var mismatch = channelLink(u, avito, CIAN_LINK);
        assertThat(mismatch.message()).isEqualTo("Похоже, это ссылка с площадки «Циан», а карточка — «Авито».");
        assertThat(mismatch.mismatch()).isTrue();
        assertThat(mismatch.url()).isEqualTo(CIAN_LINK);
        assertThat(jdbc.queryForObject("SELECT config_json->>'import_url' FROM channels WHERE id = ?", String.class, avito)).isNull();

        // своя ссылка — сохранена и сразу синхронизирована: брони в шахматке
        var saved = channelLink(u, cian, CIAN_LINK);
        assertThat(saved.saved()).isTrue();
        assertThat(saved.message()).startsWith("Нашли 2 занятых периода");
        assertThat(jdbc.queryForObject("SELECT config_json->>'import_url' FROM channels WHERE id = ?", String.class, cian))
                .isEqualTo(CIAN_LINK);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calendar_blocks WHERE channel_id = ?", Integer.class, cian))
                .isEqualTo(2);
        // «Сохранить всё равно» для ссылки с другой площадки
        assertThat(channelLink(u, avito, CIAN_LINK, "keep", "true").saved()).isTrue();

        // у площадки со ссылкой — прежняя ссылка и «Заменить ссылку»; неудачная замена прежнюю не трогает
        html = page(u, "/settings/channels");
        assertThat(html).contains("Заменить ссылку").doesNotContain("id=\"import-" + cian + "\"");
        assertThat(channelLink(u, cian, "https://calendar.example.org/missing.ics").message()).startsWith("Ссылка не открывается");
        assertThat(jdbc.queryForObject("SELECT config_json->>'import_url' FROM channels WHERE id = ?", String.class, cian))
                .isEqualTo(CIAN_LINK);

        // площадка, заведённая обычной формой (неизвестно, какая это площадка): сверять не с чем — сохраняется
        Long plain = jdbc.queryForObject("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled, export_secret)"
                + " SELECT tenant_id, unit_type_id, 'ICAL', 'Моя площадка', true, 'plain-' || id FROM channels WHERE id = ? RETURNING id",
                Long.class, cian);
        assertThat(channelLink(u, plain, CIAN_LINK).saved()).isTrue();

        // чужая площадка недоступна
        mvc.perform(post("/settings/channels/" + cian + "/import-url").with(user(newcomer())).with(csrf()).param("url", CIAN_LINK))
                .andExpect(status().isNotFound());
    }
}
