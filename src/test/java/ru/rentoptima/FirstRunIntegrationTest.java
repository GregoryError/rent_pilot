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
import ru.rentoptima.service.PropertyCreator;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
