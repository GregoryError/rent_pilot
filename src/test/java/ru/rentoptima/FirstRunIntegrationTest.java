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
        assertThat(html).contains("Здесь будет сводка по квартире").contains("/onboarding?step=property");
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
    @DisplayName("/pricing без квартиры: пустое состояние вместо 3000/4000 и пустой таблицы")
    void emptyPricing() throws Exception {
        String html = page(account(), "/pricing");
        assertThat(html).contains("Цены появятся вместе с квартирой").contains("/onboarding?step=property");
        assertThat(html).doesNotContain("3000 ₽").doesNotContain("kpi-row").doesNotContain("следующие 60 дней");
    }

    @Test
    @DisplayName("«Площадки» без квартиры: кнопка в шапке — ссылка в мастер, а не неактивная кнопка")
    void connectButtonLeadsToWizard() throws Exception {
        String html = page(account(), "/settings/channels");
        assertThat(html).contains("href=\"/onboarding?step=platforms\"");
        assertThat(html).doesNotContain("id=\"btn-add-channel\"").doesNotContain("Подключить канал");
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
    @DisplayName("две категории — понятие «категория» возвращается, выбор в формах тоже")
    void categoriesShownWhenSeveral() throws Exception {
        TenantUserDetails u = account();
        Long property = propertyCreator.createProperty(u.getTenantId(), "Мини-отель", null, "Казань");
        propertyCreator.createUnitType(u.getTenantId(), property, "Стандарт", 3, BigDecimal.valueOf(3000), null);
        propertyCreator.createUnitType(u.getTenantId(), property, "Люкс", 1, BigDecimal.valueOf(6000), null);
        String channels = page(u, "/settings/channels");
        assertThat(channels).contains("Квартира или категория номеров").contains("Мини-отель / Люкс");
        assertThat(page(u, "/settings/properties")).contains("Категории номеров");
    }
}
