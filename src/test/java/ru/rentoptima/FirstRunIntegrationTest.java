package ru.rentoptima;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.security.TenantUserDetails;
import ru.rentoptima.service.PricingEngine;
import ru.rentoptima.service.PropertyCreator;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Первое впечатление: кабинет пустого аккаунта (без квартиры) и аккаунта с одной квартирой.
 * PostgreSQL в контейнере; без docker пропускается.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.uploads.dir=target/test-uploads"})
@AutoConfigureMockMvc
@DisplayName("Пустой аккаунт: что видит новый пользователь")
class FirstRunIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PropertyCreator propertyCreator;
    @Autowired PricingEngine pricingEngine;
    @Autowired ru.rentoptima.service.WidgetCreator widgetCreator;
    @Autowired ru.rentoptima.onboarding.OnboardingService onboardingService;

    private TenantUserDetails account() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Тест', ?) RETURNING id",
                Long.class, "fr-" + tag);
        // как после регистрации: настройки по умолчанию
        jdbc.update("INSERT INTO system_settings(tenant_id, key, value) VALUES (?, 'weekday_base_price', '3000'),"
                + " (?, 'weekend_base_price', '4000'), (?, 'autopilot_mode', 'OFF')", tenantId, tenantId, tenantId);
        Long userId = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, display_name, role)"
                + " VALUES (?, ?, 'x', 'Хозяйка', 'OWNER') RETURNING id", Long.class, tenantId, tag + "@example.com");
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        User u = new User();
        u.setId(userId);
        u.setTenant(tenant);
        u.setUsername(tag + "@example.com");
        u.setPasswordHash("x");
        u.setDisplayName("Хозяйка");
        u.setRole(User.Role.OWNER);
        return new TenantUserDetails(u);
    }

    private String page(TenantUserDetails u, String url) throws Exception {
        return mvc.perform(get(url).with(user(u))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void apartment(TenantUserDetails u) {
        Long property = propertyCreator.createProperty(u.getTenantId(), "Студия", null, "Выборг");
        propertyCreator.createUnitType(u.getTenantId(), property, "Основной", 1, BigDecimal.valueOf(4500), null);
    }

    @Test
    @DisplayName("дашборд без квартиры: ни метрик, ни алертов, одна кнопка в мастер")
    void emptyDashboard() throws Exception {
        String html = page(account(), "/dashboard");
        assertThat(html).contains("Здесь появится сводка").contains("Добавить жильё").contains("/onboarding?step=property");
        assertThat(html).doesNotContain("Загрузка отстаёт").doesNotContain("Требует внимания")
                .doesNotContain("hero__value").doesNotContain("kpi-row").doesNotContain("импортируйте");
    }

    @Test
    @DisplayName("дашборд с квартирой без истории: месяц в именительном падеже, темп — «мало данных», без алерта")
    void dashboardWithoutHistory() throws Exception {
        TenantUserDetails u = account();
        apartment(u);
        String html = page(u, "/dashboard?m=2026-10");
        assertThat(html).contains("Октябрь 2026").doesNotContain("Октября 2026");
        assertThat(html).contains("Мало данных").doesNotContain("Загрузка отстаёт");
        assertThat(html).doesNotContain("NOVEMBER").doesNotContain("DECEMBER");
    }

    @Test
    @DisplayName("без жилья разделы вне короткого меню ведут в мастер на шаг жилья; «Настройки» открыты")
    void noPropertyPagesLeadToWizard() throws Exception {
        TenantUserDetails u = account();
        for (String path : new String[]{"/pricing", "/settings/channels", "/settings/channels/diagnostics",
                "/settings/widgets", "/settings/properties", "/settings/transfer", "/bookings/pending",
                "/calendar", "/expenses", "/feedback-admin", "/staff", "/chat"}) {
            mvc.perform(get(path).with(user(u))).andExpect(redirectedUrl("/onboarding?step=property"));
        }
        mvc.perform(get("/settings").with(user(u))).andExpect(status().isOk());
        mvc.perform(get("/settings/integrations").with(user(u))).andExpect(status().isOk());
        mvc.perform(get("/account/password").with(user(u))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("«Площадки» без площадок: в шапке нет кнопок, одна кнопка в пустом состоянии ведёт в мастер")
    void emptyPlatformsOneButton() throws Exception {
        TenantUserDetails u = account();
        apartment(u);
        String html = page(u, "/settings/channels");
        String header = html.substring(html.indexOf("page-header"), html.indexOf("Площадок пока нет"));
        assertThat(header).doesNotContain("Диагностика").doesNotContain("Подключить площадку");
        assertThat(html).contains("href=\"/onboarding?step=platforms\"").doesNotContain("Подключить канал");
    }

    @Test
    @DisplayName("рекомендации считаются от цены квартиры, а не от настроек 3000/4000; выходные — ночи с пт и с сб")
    void recommendationsUseApartmentPrice() throws Exception {
        TenantUserDetails u = account();   // в настройках 3000 / 4000, как у старых аккаунтов
        Long property = propertyCreator.createProperty(u.getTenantId(), "Студия", null, "Выборг");
        propertyCreator.createUnitType(u.getTenantId(), property, "Основной", 1,
                BigDecimal.valueOf(4500), BigDecimal.valueOf(6000));
        var p = jdbc.queryForObject("SELECT id FROM properties WHERE id = ?", Long.class, property);
        List<PricingEngine.PricingRecommendation> recs = pricingEngine.getRecommendations(u.getTenantId(), p);
        LocalDate friday = LocalDate.now().plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        assertThat(base(recs, friday)).isEqualTo(6000);
        assertThat(base(recs, friday.plusDays(1))).isEqualTo(6000);   // суббота
        assertThat(base(recs, friday.plusDays(2))).isEqualTo(4500);   // воскресенье — ночь будней
        assertThat(base(recs, friday.plusDays(3))).isEqualTo(4500);
        assertThat(recs).noneMatch(r -> r.currentPrice().intValue() == 3000 || r.currentPrice().intValue() == 4000);

        String html = page(u, "/pricing");
        assertThat(html).contains("4500 ₽").contains("6000 ₽").doesNotContain(">3000 ₽<");
    }

    @Test
    @DisplayName("пределы из старых настроек ниже цены квартиры — предупреждение на /pricing; пустые — 50–250% цены")
    void boundsWarningAndRelativeBounds() throws Exception {
        TenantUserDetails u = account();
        jdbc.update("INSERT INTO system_settings(tenant_id, key, value) VALUES (?, 'min_price_floor', '2000'),"
                + " (?, 'max_price_ceiling', '8000')", u.getTenantId(), u.getTenantId());
        Long property = propertyCreator.createProperty(u.getTenantId(), "Лофт", null, "Москва");
        propertyCreator.createUnitType(u.getTenantId(), property, "Основной", 1, BigDecimal.valueOf(12000), null);
        assertThat(page(u, "/pricing")).contains("выше максимальной цены в настройках");
        assertThat(pricingEngine.getRecommendations(u.getTenantId(), property))
                .allMatch(r -> r.recommendedPrice().intValue() <= 8000);

        jdbc.update("UPDATE system_settings SET value = '' WHERE tenant_id = ? AND key IN ('min_price_floor', 'max_price_ceiling')",
                u.getTenantId());
        assertThat(page(u, "/pricing")).doesNotContain("выше максимальной цены в настройках");
        assertThat(pricingEngine.getRecommendations(u.getTenantId(), property))
                .allMatch(r -> r.recommendedPrice().intValue() >= 6000 && r.recommendedPrice().intValue() <= 30000)
                .anyMatch(r -> r.recommendedPrice().intValue() > 8000);
    }

    @Test
    @DisplayName("у квартиры нет цены — рекомендаций нет и /pricing просит задать цену")
    void apartmentWithoutPrice() throws Exception {
        TenantUserDetails u = account();
        Long property = propertyCreator.createProperty(u.getTenantId(), "Без цены", null, "Тверь");
        propertyCreator.createUnitType(u.getTenantId(), property, "Основной", 1, null, null);
        assertThat(pricingEngine.getRecommendations(u.getTenantId(), property)).isEmpty();
        assertThat(page(u, "/pricing")).contains("не задана цена за ночь");
    }

    private static int base(List<PricingEngine.PricingRecommendation> recs, LocalDate d) {
        return recs.stream().filter(r -> r.date().equals(d)).findFirst().orElseThrow().currentPrice().intValue();
    }

    @Test
    @DisplayName("дашборд и /pricing по-русски: без estimated, Net RevPAR, Booking Pace, OFF, Net/ночь, URL")
    void russianMetrics() throws Exception {
        TenantUserDetails u = account();
        apartment(u);
        String dashboard = page(u, "/dashboard");
        assertThat(dashboard).doesNotContain("estimated").doesNotContain("RevPAR").doesNotContain("Booking Pace");
        assertThat(dashboard).contains("Доход на ночь в календаре").contains("Темп бронирований")
                .contains("class=\"hint\"").contains("оценка");
        String pricing = page(u, "/pricing");
        assertThat(pricing).doesNotContain(">OFF<").doesNotContain("Net/ночь").doesNotContain(">URL<")
                .doesNotContain("Gap (окно");
        assertThat(pricing).contains("Выключен").contains("Чистыми за ночь");
    }

    @Test
    @DisplayName("одна квартира: нет слова «категория», выбор квартиры в формах подставляется сам; цена меняется в «Объектах»")
    void simpleModeHidesCategories() throws Exception {
        TenantUserDetails u = account();
        apartment(u);
        String channels = page(u, "/settings/channels");
        assertThat(channels).doesNotContainPattern("[Кк]атегори").doesNotContainPattern("[Кк]анал");
        assertThat(channels).containsPattern("type=\"hidden\" name=\"unitTypeId\"");
        String widgets = page(u, "/settings/widgets");
        assertThat(widgets).doesNotContainPattern("[Кк]атегори");
        String properties = page(u, "/settings/properties");
        assertThat(properties).contains("Цена за ночь").doesNotContain("Категории номеров").contains("/edit\"");
        assertThat(page(u, "/calendar/grid")).contains(">Квартира<").doesNotContain("Объект / категория");

        // форма «Изменить» под ценой: имя и количество уходят скрытыми полями
        var unit = jdbc.queryForMap("SELECT id, property_id, name FROM unit_types WHERE tenant_id = ?", u.getTenantId());
        mvc.perform(post("/settings/properties/" + unit.get("property_id") + "/unit-types/" + unit.get("id") + "/edit")
                        .with(user(u)).with(csrf()).param("name", (String) unit.get("name")).param("unitCount", "1")
                        .param("basePrice", "5200").param("weekendPrice", "6100"))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForMap("SELECT base_price, weekend_price, unit_count FROM unit_types WHERE id = ?", unit.get("id")))
                .containsEntry("unit_count", 1);
        assertThat(jdbc.queryForObject("SELECT base_price FROM unit_types WHERE id = ?", BigDecimal.class, unit.get("id")))
                .isEqualByComparingTo("5200");
        assertThat(page(u, "/settings/properties")).contains("5 200 ₽").contains("6 100 ₽");
    }

    @Test
    @DisplayName("мини-отель с двумя типами номеров: «гостиница», «типы номеров», выбор в формах")
    void categoriesShownWhenSeveral() throws Exception {
        TenantUserDetails u = account();
        Long property = propertyCreator.createProperty(u.getTenantId(), "Мини-отель", null, "Казань",
                ru.rentoptima.housing.HousingKind.ROOMS);
        propertyCreator.createUnitType(u.getTenantId(), property, "Стандарт", 3, BigDecimal.valueOf(3000), null);
        propertyCreator.createUnitType(u.getTenantId(), property, "Люкс", 1, BigDecimal.valueOf(6000), null);
        String channels = page(u, "/settings/channels");
        assertThat(channels).contains("Гостиница или тип номера").contains("Мини-отель / Люкс");
        // слова «квартира» нет нигде, кроме варианта в списке «Что вы сдаёте»
        assertThat(page(u, "/settings/properties").replaceAll("<option[^>]*>[^<]*</option>", ""))
                .contains("Типы номеров").contains("+ Тип номера").doesNotContainPattern("[Кк]вартир");
    }

    @Test
    @DisplayName("меню без жилья: Главная, Добавить жильё, Настройки, Помощь; шахматка уводит на Главную")
    void shortMenuWithoutApartment() throws Exception {
        TenantUserDetails u = account();
        String html = page(u, "/dashboard");
        String nav = html.substring(html.indexOf("sidebar__nav"), html.indexOf("sidebar__footer"));
        assertThat(nav).contains("Главная").contains("Добавить жильё").contains("Настройки").contains("Помощь")
                .contains("Инструкции").doesNotContain("Шахматка").doesNotContain("Сменить пароль")
                .doesNotContain("Расходы").doesNotContain("Импорт").doesNotContain("Знакомство");
        // «Сменить пароль» — в блоке аккаунта, рядом с темой и «Выйти»
        String footer = html.substring(html.indexOf("sidebar__footer"));
        assertThat(footer).contains("Сменить пароль").contains("Выйти");
        mvc.perform(get("/calendar/grid").with(user(u))).andExpect(redirectedUrl("/dashboard"));
    }

    @Test
    @DisplayName("меню с квартирой: основные разделы, остальное в «Ещё»; «Импорта» нет")
    void fullMenuWithApartment() throws Exception {
        TenantUserDetails u = account();
        apartment(u);
        String html = page(u, "/calendar/grid");
        String nav = html.substring(html.indexOf("sidebar__nav"), html.indexOf("sidebar__footer"));
        assertThat(nav).contains("Шахматка", "Площадки", "Цены", "Страница бронирования", "Заявки с сайта", "Настройки")
                .doesNotContain("Добавить квартиру").doesNotContain("Импорт");
        String more = nav.substring(nav.indexOf("id=\"nav-more\""), nav.indexOf("</details>"));
        assertThat(more).contains("Календарь цен", "Расходы", "Отзывы", "Сотрудники", "AI-чат", "Перенос данных");
        assertThat(nav).doesNotContain("id=\"nav-more\" open");
        // открыт раздел из «Ещё» — группа раскрыта
        String expenses = page(u, "/expenses");
        assertThat(expenses).containsPattern("<details class=\"nav-more\" id=\"nav-more\" open");
    }

    @Test
    @DisplayName("перенос истории: /import → 301; страница про RealtyCalendar без вебхуков; очистка не трогает ручные брони")
    void transferPage() throws Exception {
        TenantUserDetails u = account();
        apartment(u);
        mvc.perform(get("/import").with(user(u))).andExpect(status().isMovedPermanently())
                .andExpect(redirectedUrl("/settings/transfer"));
        String html = page(u, "/settings/transfer");
        assertThat(html).contains("Перенести историю из RealtyCalendar").doesNotContainPattern("[Вв]ебхук");

        Long property = jdbc.queryForObject("SELECT id FROM properties WHERE tenant_id = ?", Long.class, u.getTenantId());
        java.time.LocalDate d = java.time.LocalDate.now().minusDays(40);
        for (String source : new String[]{null, "RC", "MANUAL", "WIDGET"}) {
            jdbc.update("INSERT INTO bookings(tenant_id, property_id, check_in, check_out, nights, status, data_source)"
                    + " VALUES (?, ?, ?, ?, 1, 'BOOKED', ?)", u.getTenantId(), property, d, d.plusDays(1), source);
            d = d.plusDays(2);
        }
        mvc.perform(post("/settings/transfer/clear").with(user(u)).with(csrf()).param("propertyId", property.toString()))
                .andExpect(redirectedUrl("/settings/transfer"));
        assertThat(jdbc.queryForList("SELECT data_source FROM bookings WHERE property_id = ? ORDER BY data_source",
                String.class, property)).containsExactly("MANUAL", "WIDGET");
    }

    @Test
    @DisplayName("настройки: в «Основных» уборка и комиссия, остальное в свёрнутых «Продвинутых»")
    void settingsAdvancedCollapsed() throws Exception {
        TenantUserDetails u = account();
        jdbc.update("INSERT INTO system_settings(tenant_id, key, value) VALUES (?, 'cleaning_cost', '1500'),"
                + " (?, 'open_ahead_days', '75'), (?, 'city', '')", u.getTenantId(), u.getTenantId(), u.getTenantId());
        apartment(u);
        String html = page(u, "/settings");
        int advanced = html.indexOf("id=\"settings-advanced\"");
        assertThat(advanced).isPositive();
        assertThat(html.substring(0, advanced)).contains("name=\"cleaning_cost\"").doesNotContain("name=\"open_ahead_days\"")
                .doesNotContain("name=\"weekday_base_price\"");
        assertThat(html).containsPattern("<details class=\"card settings-advanced\" id=\"settings-advanced\">")
                .contains("Значения по умолчанию подходят большинству")
                .contains("Значения по умолчанию для нового жилья").contains("/settings/transfer");
        assertThat(html.substring(advanced)).contains("name=\"open_ahead_days\"").contains("name=\"weekday_base_price\"");
    }

    @Test
    @DisplayName("с жильём на каждой странице кабинета подсвечен пункт меню; страницы из «Ещё» раскрывают группу")
    void everyPageHighlightsMenu() throws Exception {
        TenantUserDetails u = account();
        apartment(u);
        Long unit = jdbc.queryForObject("SELECT id FROM unit_types WHERE tenant_id = ?", Long.class, u.getTenantId());
        Long widget = widgetCreator.create(u.getTenantId(), unit, "Страница").getId();
        String w = "/settings/widgets/" + widget;
        java.util.Map<String, Boolean> pages = new java.util.LinkedHashMap<>();   // адрес → пункт в «Ещё»
        for (String p : new String[]{"/dashboard", "/calendar/grid", "/settings/channels", "/settings/channels/diagnostics",
                "/pricing", "/settings/widgets", w, w + "/stats", w + "/design", w + "/card", "/bookings/pending",
                "/settings", "/settings/properties", "/settings/integrations"}) {
            pages.put(p, false);
        }
        for (String p : new String[]{"/calendar", "/expenses", "/feedback-admin", "/feedback", "/staff", "/chat",
                "/settings/transfer"}) {
            pages.put(p, true);
        }
        for (var e : pages.entrySet()) {
            String html = page(u, e.getKey());
            assertThat(html).as(e.getKey()).contains("nav-item--active");
            assertThat(html.contains("<details class=\"nav-more\" id=\"nav-more\" open")).as(e.getKey() + " — «Ещё»")
                    .isEqualTo(e.getValue());
        }
        // «Сменить пароль» — в блоке аккаунта, подсвечен там
        assertThat(page(u, "/account/password")).contains("sidebar__account-link is-active");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(ru.rentoptima.housing.HousingKind.class)
    @DisplayName("тип жилья в словах кабинета: «Объекты», «Цены», меню «Ещё», «Изменить цену»")
    void wordsByKind(ru.rentoptima.housing.HousingKind kind) throws Exception {
        TenantUserDetails u = account();
        Long property = propertyCreator.createProperty(u.getTenantId(), "Объект", null, "Город", kind);
        propertyCreator.createUnitType(u.getTenantId(), property, "Основной", 1, BigDecimal.valueOf(4500), null);
        ru.rentoptima.housing.Housing h = kind.housing;
        // один объект на одну единицу — «простой режим»: «Ваша квартира», «Ваш дом», «Ваши апартаменты»
        assertThat(page(u, "/settings/properties")).contains(h.fill("%Your%: название, адрес и цена за ночь"));
        assertThat(page(u, "/pricing")).contains(h.fill("Цена %gen% в будни"));
        assertThat(page(u, "/calendar/grid")).contains(">" + h.fill("%Nom%") + "<");
        if (kind != ru.rentoptima.housing.HousingKind.APARTMENT) {
            String props = page(u, "/settings/properties").replaceAll("<option[^>]*>[^<]*</option>", "");
            assertThat(props).as(kind.name()).doesNotContainPattern("[Кк]вартир");
        }
    }

    @Test
    @DisplayName("тип жилья выбирается в мастере и меняется в «Объектах»")
    void kindChosenAndChanged() throws Exception {
        TenantUserDetails u = account();
        onboardingService.begin(u.getUserId(), u.getTenantId());
        mvc.perform(post("/onboarding/branch").with(user(u)).with(csrf()).param("branch", "B"));
        assertThat(page(u, "/onboarding?step=property")).contains("Что вы сдаёте?", "value=\"HOUSE\"", "value=\"ROOMS\"");
        mvc.perform(post("/onboarding/property").with(user(u)).with(csrf()).param("kind", "HOUSE")
                .param("name", "Дом у озера").param("city", "Тверь").param("basePrice", "9000"));
        Long id = jdbc.queryForObject("SELECT id FROM properties WHERE tenant_id = ?", Long.class, u.getTenantId());
        assertThat(jdbc.queryForObject("SELECT kind FROM properties WHERE id = ?", String.class, id)).isEqualTo("HOUSE");
        mvc.perform(post("/settings/properties/" + id + "/edit").with(user(u)).with(csrf())
                .param("name", "Дом у озера").param("kind", "APARTMENTS")).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT kind FROM properties WHERE id = ?", String.class, id)).isEqualTo("APARTMENTS");
    }
}
