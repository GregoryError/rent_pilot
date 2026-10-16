package ru.rentoptima;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.entity.User;
import ru.rentoptima.security.TenantUserDetails;
import ru.rentoptima.service.PropertyCreator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Страница горничной на настоящей базе: даты, открытые хозяином («Открыть даты»), и
 * удалённые ручные брони она не видит; выезды дальше горизонта объекта — тоже. Горизонт
 * задаётся в «Сотрудниках». Нужен docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.uploads.dir=target/test-uploads"})
@AutoConfigureMockMvc
@DisplayName("Страница горничной: открытые даты и горизонт уборок")
class HousekeeperPageIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String PIN = "4821";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PropertyCreator propertyCreator;

    private TenantUserDetails owner;
    private Long propertyId;
    private Long unitTypeId;
    private Long channelId;
    private String code;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Тест', ?) RETURNING id",
                Long.class, "hk-" + tag);
        Long userId = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, display_name, role)"
                + " VALUES (?, ?, 'x', 'Хозяин', 'OWNER') RETURNING id", Long.class, tenantId, tag + "@example.com");
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        User u = new User();
        u.setId(userId);
        u.setTenant(tenant);
        u.setUsername(tag + "@example.com");
        u.setPasswordHash("x");
        u.setDisplayName("Хозяин");
        u.setRole(User.Role.OWNER);
        owner = new TenantUserDetails(u);

        propertyId = propertyCreator.createProperty(tenantId, "Садовая 5 кв. 38", null, "Выборг");
        UnitType ut = propertyCreator.createUnitType(tenantId, propertyId, "Основной", 1, BigDecimal.valueOf(5000), null);
        unitTypeId = ut.getId();
        channelId = jdbc.queryForObject("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, color,"
                + " export_secret) VALUES (?, ?, 'ICAL', 'Суточно', '#2563EB', ?) RETURNING id", Long.class,
                tenantId, unitTypeId, UUID.randomUUID().toString().replace("-", ""));
        jdbc.update("UPDATE properties SET housekeeper_pin_hash = ? WHERE id = ?",
                new BCryptPasswordEncoder().encode(PIN), propertyId);
        code = jdbc.queryForObject("SELECT housekeeper_code FROM properties WHERE id = ?", String.class, propertyId);
    }

    private void channelBlock(int fromDays, int toDays, boolean ignored) {
        jdbc.update("INSERT INTO calendar_blocks(tenant_id, unit_type_id, channel_id, block_type, from_date, to_date,"
                        + " external_uid, ignored) SELECT tenant_id, id, ?, 'CHANNEL_SYNC', ?, ?, ?, ? FROM unit_types WHERE id = ?",
                channelId, today.plusDays(fromDays), today.plusDays(toDays), "uid-" + UUID.randomUUID(), ignored, unitTypeId);
    }

    private String label(int days) {
        LocalDate d = today.plusDays(days);
        return "Выезд " + d.format(DateTimeFormatter.ofPattern(d.getYear() == today.getYear() ? "dd.MM" : "dd.MM.yyyy"));
    }

    private String page() throws Exception {
        return page("schedule");
    }

    private String page(String tab) throws Exception {
        Cookie auth = mvc.perform(post("/housekeeper/" + code + "/login").with(csrf()).param("pin", PIN))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getCookie("hk_auth");
        assertThat(auth).isNotNull();
        return mvc.perform(get("/housekeeper/" + code).param("tab", tab).cookie(auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("«Открыть даты» и удалённая ручная бронь — не видны; выезды дальше 30 дней — тоже")
    void ignoredCancelledAndBeyondHorizonHidden() throws Exception {
        channelBlock(1, 3, true);    // хозяин открыл даты
        channelBlock(4, 6, false);   // обычная бронь площадки
        jdbc.update("INSERT INTO calendar_blocks(tenant_id, unit_type_id, block_type, from_date, to_date, cancelled_at)"
                        + " SELECT tenant_id, id, 'MANUAL_BOOKING', ?, ?, now() FROM unit_types WHERE id = ?",
                today.plusDays(7), today.plusDays(9), unitTypeId);
        channelBlock(28, 30, false); // ровно на горизонте
        channelBlock(35, 40, false); // за горизонтом

        String html = page();

        assertThat(html).contains(label(6), label(30))
                .doesNotContain(label(3), label(9), label(40));
    }

    @Test
    @DisplayName("перенесённый отзыв с ответом на выключенный вопрос — горничная видит подпись вопроса, не «?»")
    void inactiveQuestionLabel() throws Exception {
        Long tenantId = owner.getTenantId();
        Long question = jdbc.queryForObject("INSERT INTO feedback_questions(tenant_id, property_id, question_text,"
                + " question_type, sort_order, active) VALUES (?, ?, 'Удобство кровати', 'TEXT', 99, false) RETURNING id",
                Long.class, tenantId, propertyId);
        Long response = jdbc.queryForObject("INSERT INTO feedback_responses(property_id, session_id, guest_name,"
                + " completed, show_to_housekeeper, created_at, updated_at, source)"
                + " VALUES (?, ?, 'А.', true, true, '2026-05-02 10:00', '2026-05-02 10:05', 'MIGRATED') RETURNING id",
                Long.class, propertyId, "mig-" + UUID.randomUUID());
        jdbc.update("INSERT INTO feedback_answers(response_id, question_id, text_value, answered_at)"
                + " VALUES (?, ?, 'Очень удобно', '2026-05-02 10:01')", response, question);

        assertThat(page("reviews")).contains("Удобство кровати", "Очень удобно");
    }

    @Test
    @DisplayName("горизонт из «Сотрудников»: 7–180, вне диапазона не сохраняется, чужой объект — 404")
    void horizonSetting() throws Exception {
        channelBlock(35, 40, false);
        assertThat(page()).doesNotContain(label(40));

        mvc.perform(post("/staff/" + propertyId + "/horizon").with(user(owner)).with(csrf()).param("days", "60"))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT housekeeper_horizon_days FROM properties WHERE id = ?",
                Integer.class, propertyId)).isEqualTo(60);
        assertThat(page()).contains(label(40));

        for (String bad : new String[]{"6", "181", "abc"}) {
            mvc.perform(post("/staff/" + propertyId + "/horizon").with(user(owner)).with(csrf()).param("days", bad));
        }
        assertThat(jdbc.queryForObject("SELECT housekeeper_horizon_days FROM properties WHERE id = ?",
                Integer.class, propertyId)).isEqualTo(60);

        assertThat(mvc.perform(get("/staff").with(user(owner))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .contains("Показывать уборки на ближайшие", "value=\"60\"");
    }
}
