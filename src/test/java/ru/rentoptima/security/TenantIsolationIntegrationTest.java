package ru.rentoptima.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.entity.User;
import ru.rentoptima.onboarding.OnboardingService;
import ru.rentoptima.service.PropertyCreator;
import ru.rentoptima.service.WidgetCreator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Изоляция тенантов: пользователь A не может прочитать, изменить или удалить объект
 * пользователя B, подставив его id в адрес или форму. Чужой объект — 404, как
 * несуществующий. После всех попыток данные B не изменились. Нужен docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.uploads.dir=target/test-uploads"})
@AutoConfigureMockMvc
@DisplayName("Изоляция тенантов: чужой объект по id — 404, данные владельца не меняются")
class TenantIsolationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PropertyCreator propertyCreator;
    @Autowired WidgetCreator widgetCreator;
    @Autowired OnboardingService onboarding;

    /** Объекты владельца B. */
    record Owned(Long property, Long unitType, Long channel, Long channelBlock, Long manualBlock, Long manualBooking,
                 Long pendingBooking, Long widget, Long photo, Long promo, Long category, Long feedback,
                 Long competitor, Long override) {}

    private TenantUserDetails a;
    private TenantUserDetails b;
    private Owned theirs;
    private Owned mine;

    @BeforeEach
    void accounts() {
        a = account();
        b = account();
        theirs = objects(b);
        mine = objects(a);
    }

    private TenantUserDetails account() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Тест', ?) RETURNING id",
                Long.class, "iso-" + tag);
        Long userId = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, display_name, role)"
                + " VALUES (?, ?, 'x', 'Хозяин', 'OWNER') RETURNING id", Long.class, tenantId, tag + "@example.com");
        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        User user = new User();
        user.setId(userId);
        user.setTenant(tenant);
        user.setUsername(tag + "@example.com");
        user.setPasswordHash("x");
        user.setDisplayName("Хозяин");
        user.setRole(User.Role.OWNER);
        return new TenantUserDetails(user);
    }

    private Owned objects(TenantUserDetails u) {
        Long t = u.getTenantId();
        Long property = propertyCreator.createProperty(t, "Квартира", null, "Город");
        UnitType ut = propertyCreator.createUnitType(t, property, "Основной", 1, BigDecimal.valueOf(5000), null);
        Long channel = jdbc.queryForObject("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, color,"
                + " export_secret) VALUES (?, ?, 'ICAL', 'Площадка', '#2563EB', ?) RETURNING id", Long.class,
                t, ut.getId(), UUID.randomUUID().toString().replace("-", ""));
        LocalDate d = LocalDate.now().plusDays(10);
        Long channelBlock = jdbc.queryForObject("INSERT INTO calendar_blocks(tenant_id, unit_type_id, channel_id,"
                + " block_type, from_date, to_date, external_uid) VALUES (?, ?, ?, 'CHANNEL_SYNC', ?, ?, ?) RETURNING id",
                Long.class, t, ut.getId(), channel, d, d.plusDays(2), "uid-" + UUID.randomUUID());
        Long manualBlock = jdbc.queryForObject("INSERT INTO calendar_blocks(tenant_id, unit_type_id, block_type,"
                + " from_date, to_date) VALUES (?, ?, 'MAINTENANCE', ?, ?) RETURNING id",
                Long.class, t, ut.getId(), d.plusDays(5), d.plusDays(6));
        Long manualBooking = jdbc.queryForObject("INSERT INTO bookings(tenant_id, property_id, unit_type_id, check_in,"
                + " check_out, nights, status, source, data_source) VALUES (?, ?, ?, ?, ?, 1, 'BOOKED', 'manual', 'MANUAL')"
                + " RETURNING id", Long.class, t, property, ut.getId(), d.plusDays(20), d.plusDays(21));
        BookingWidget widget = widgetCreator.create(t, ut.getId(), "Страница");
        Long pendingBooking = jdbc.queryForObject("INSERT INTO bookings(tenant_id, property_id, unit_type_id, channel_id,"
                + " check_in, check_out, nights, status, source, data_source) VALUES (?, ?, ?, ?, ?, ?, 2, 'PENDING',"
                + " 'DIRECT_WIDGET', 'WIDGET') RETURNING id", Long.class,
                t, property, ut.getId(), widget.getChannelId(), d.plusDays(30), d.plusDays(32));
        Long photo = jdbc.queryForObject("INSERT INTO widget_photos(tenant_id, widget_id, file_key, width, height,"
                + " widths, lqip) VALUES (?, ?, ?, 480, 320, '480', 'data:') RETURNING id", Long.class,
                t, widget.getId(), UUID.randomUUID().toString().replace("-", ""));
        Long promo = jdbc.queryForObject("INSERT INTO promo_codes(tenant_id, widget_id, code, discount_type,"
                + " discount_value) VALUES (?, ?, 'SALE', 'PERCENT', 10) RETURNING id", Long.class, t, widget.getId());
        Long category = jdbc.queryForObject("INSERT INTO expense_categories(tenant_id, name) VALUES (?, 'Ремонт')"
                + " RETURNING id", Long.class, t);
        Long feedback = jdbc.queryForObject("INSERT INTO feedback_responses(property_id, session_id, completed)"
                + " VALUES (?, ?, true) RETURNING id", Long.class, property, UUID.randomUUID().toString());
        Long competitor = jdbc.queryForObject("INSERT INTO competitor_listings(tenant_id, property_id, competitor_name,"
                + " url) VALUES (?, ?, 'Сосед', 'https://example.com/1') RETURNING id", Long.class, t, property);
        Long override = jdbc.queryForObject("INSERT INTO manual_overrides(tenant_id, property_id, override_type,"
                + " params_json) VALUES (?, ?, 'price_multiplier', '{\"multiplier\": 1.1}') RETURNING id",
                Long.class, t, property);
        return new Owned(property, ut.getId(), channel, channelBlock, manualBlock, manualBooking, pendingBooking,
                widget.getId(), photo, promo, category, feedback, competitor, override);
    }

    /** Запрос от пользователя A — ответ 404. */
    private void notFound(MockHttpServletRequestBuilder request) throws Exception {
        RequestBuilder r = request.with(user(a)).with(csrf());
        mvc.perform(r).andExpect(status().isNotFound());
    }

    /** Снимок данных B, который ни одна попытка A не должна изменить. */
    private Map<String, Object> snapshot() {
        Owned o = theirs;
        return jdbc.queryForMap("SELECT"
                + " (SELECT name || active FROM properties WHERE id = ?) AS property,"
                + " (SELECT name || active || coalesce(base_price, 0) FROM unit_types WHERE id = ?) AS unit_type,"
                + " (SELECT count(*) FROM unit_types WHERE property_id = ?) AS unit_types,"
                + " (SELECT active::text || sync_enabled || color || export_secret FROM channels WHERE id = ?) AS channel,"
                + " (SELECT coalesce(ignored, false)::text FROM calendar_blocks WHERE id = ?) AS channel_block,"
                + " (SELECT coalesce(cancelled_at::text, 'live') FROM calendar_blocks WHERE id = ?) AS manual_block,"
                + " (SELECT count(*) FROM calendar_blocks WHERE tenant_id = ?) AS blocks,"
                + " (SELECT status FROM bookings WHERE id = ?) AS manual_booking,"
                + " (SELECT status FROM bookings WHERE id = ?) AS pending_booking,"
                + " (SELECT count(*) FROM bookings WHERE tenant_id = ?) AS bookings,"
                + " (SELECT active::text || secret || title FROM booking_widgets WHERE id = ?) AS widget,"
                + " (SELECT count(*) FROM widget_photos WHERE id = ?) AS photo,"
                + " (SELECT position FROM widget_photos WHERE id = ?) AS photo_position,"
                + " (SELECT active::text FROM promo_codes WHERE id = ?) AS promo,"
                + " (SELECT count(*) FROM promo_codes WHERE widget_id = ?) AS promos,"
                + " (SELECT count(*) FROM widget_cards WHERE widget_id = ?) AS cards,"
                + " (SELECT count(*) FROM expenses WHERE property_id = ? OR category_id = ?) AS expenses,"
                + " (SELECT show_to_housekeeper::text FROM feedback_responses WHERE id = ?) AS feedback,"
                + " (SELECT active::text FROM competitor_listings WHERE id = ?) AS competitor,"
                + " (SELECT coalesce(housekeeper_pin_hash, 'none') FROM properties WHERE id = ?) AS pin,"
                + " (SELECT active::text FROM manual_overrides WHERE id = ?) AS override,"
                + " (SELECT count(*) FROM planned_prices WHERE unit_type_id = ?) AS prices",
                o.property(), o.unitType(), o.property(), o.channel(), o.channelBlock(), o.manualBlock(), b.getTenantId(),
                o.manualBooking(), o.pendingBooking(), b.getTenantId(), o.widget(), o.photo(), o.photo(), o.promo(),
                o.widget(), o.widget(), o.property(), o.category(), o.feedback(), o.competitor(), o.property(),
                o.override(), o.unitType());
    }

    @Test
    @DisplayName("объекты и категории: прочитать, изменить, удалить чужое — 404")
    void propertiesAndUnitTypes() throws Exception {
        Map<String, Object> before = snapshot();
        Owned o = theirs;
        notFound(post("/settings/properties/" + o.property() + "/edit").param("name", "Взлом"));
        notFound(post("/settings/properties/" + o.property() + "/delete"));
        notFound(get("/feedback-admin").param("property", o.property().toString()));
        notFound(post("/settings/properties/" + o.property() + "/unit-types").param("name", "Взлом"));
        notFound(post("/settings/properties/" + o.property() + "/unit-types/" + o.unitType() + "/edit")
                .param("name", "Взлом").param("basePrice", "1"));
        // своя категория A под чужим объектом в адресе — тоже не найдена
        notFound(post("/settings/properties/" + o.property() + "/unit-types/" + mine.unitType() + "/edit")
                .param("name", "Взлом"));
        notFound(post("/settings/properties/" + o.property() + "/unit-types/" + o.unitType() + "/delete"));
        notFound(post("/staff/" + o.property() + "/pin").param("pin", "123456"));
        notFound(post("/staff/" + o.property() + "/horizon").param("days", "90"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("площадки: синхронизация, вкл/выкл, цвет, секрет, ссылка, удаление, создание на чужой категории — 404")
    void channels() throws Exception {
        Map<String, Object> before = snapshot();
        Owned o = theirs;
        for (String action : new String[]{"sync", "toggle", "regenerate-secret", "delete"}) {
            notFound(post("/settings/channels/" + o.channel() + "/" + action));
        }
        notFound(post("/settings/channels/" + o.channel() + "/color").param("color", "#16A34A"));
        notFound(post("/settings/channels/" + o.channel() + "/import-url").param("url", "https://www.cian.ru/x.ics"));
        notFound(post("/settings/channels").param("name", "Взлом").param("unitTypeId", o.unitType().toString())
                .param("importUrl", "https://example.com/a.ics"));
        onboarding.begin(a.getUserId(), a.getTenantId());
        notFound(post("/onboarding/links/" + o.channel()).param("url", "https://www.cian.ru/x.ics"));
        notFound(post("/onboarding/links/" + o.channel() + "/later"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("шахматка и брони: записи дня, создание, удаление, открытие дат, заявки — 404")
    void calendarAndBookings() throws Exception {
        Map<String, Object> before = snapshot();
        Owned o = theirs;
        String day = LocalDate.now().plusDays(10).toString();
        notFound(get("/calendar/grid/action/entries").param("unitTypeId", o.unitType().toString()).param("date", day));
        notFound(post("/calendar/grid/action").param("unitTypeId", o.unitType().toString())
                .param("fromDate", day).param("toDate", day).param("createEntry", "true")
                .param("entryType", "MAINTENANCE").param("setPrice", "true").param("plannedPrice", "1"));
        notFound(post("/calendar/grid/action/delete").param("entry", "block:" + o.manualBlock()));
        notFound(post("/calendar/grid/action/delete").param("entry", "booking:" + o.manualBooking()));
        notFound(post("/calendar/grid/action/toggle-channel-block").param("entry", "channel:" + o.channelBlock()));
        for (String action : new String[]{"confirm", "decline", "cancel"}) {
            notFound(post("/bookings/pending/" + o.pendingBooking() + "/" + action));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("страница бронирования, фото, промокоды, карточка: чтение и изменения — 404")
    void widgets() throws Exception {
        Map<String, Object> before = snapshot();
        Owned o = theirs;
        String w = "/settings/widgets/" + o.widget();
        for (String page : new String[]{"", "/stats", "/design", "/frame", "/card", "/card/print", "/card/qr.svg"}) {
            notFound(get(w + page));
        }
        notFound(post(w + "/update").param("title", "Взлом").param("minNights", "1").param("maxNights", "30")
                .param("maxGuests", "2").param("bookingWindowDays", "180").param("holdHours", "24")
                .param("checkinTime", "14:00").param("checkoutTime", "12:00"));
        notFound(post(w + "/design").param("config", "{}"));
        notFound(post(w + "/regenerate"));
        notFound(post(w + "/delete"));
        notFound(post(w + "/promo").param("code", "HACK").param("discountType", "PERCENT").param("discountValue", "50"));
        notFound(post(w + "/promo/" + o.promo() + "/toggle"));
        // чужой промокод под своей страницей A
        notFound(post("/settings/widgets/" + mine.widget() + "/promo/" + o.promo() + "/toggle"));
        notFound(multipart(w + "/photos").file(new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1})));
        notFound(post(w + "/photos/" + o.photo() + "/delete"));
        notFound(post(w + "/photos/" + o.photo() + "/move").param("dir", "down"));
        notFound(post("/settings/widgets/" + mine.widget() + "/photos/" + o.photo() + "/delete"));
        notFound(post("/settings/widgets/" + mine.widget() + "/photos/" + o.photo() + "/move").param("dir", "up"));
        notFound(post(w + "/card").param("title", "Взлом"));
        notFound(post(w + "/card/promo").param("discountValue", "10"));
        notFound(post(w + "/card/regenerate"));
        notFound(post(w + "/card/wifi-qr").param("ssid", "net"));
        notFound(post("/settings/widgets/create").param("title", "Взлом").param("unitTypeId", o.unitType().toString()));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("расходы, отзывы, конкуренты, перенос данных, правила AI — 404")
    void expensesFeedbackCompetitorsImport() throws Exception {
        Map<String, Object> before = snapshot();
        Owned o = theirs;
        String today = LocalDate.now().toString();
        notFound(post("/expenses").param("propertyId", o.property().toString())
                .param("categoryId", mine.category().toString()).param("amount", "100").param("date", today));
        notFound(post("/expenses").param("propertyId", mine.property().toString())
                .param("categoryId", o.category().toString()).param("amount", "100").param("date", today));
        notFound(post("/feedback-admin/" + o.feedback() + "/toggle-housekeeper"));
        notFound(post("/pricing/competitors/" + o.competitor() + "/delete"));
        notFound(post("/pricing/competitors/" + o.competitor() + "/scrape"));
        notFound(multipart("/settings/transfer").file(new MockMultipartFile("file", "a.xlsx", "application/octet-stream", new byte[]{1}))
                .param("propertyId", o.property().toString()));
        notFound(post("/settings/transfer/clear").param("propertyId", o.property().toString()));
        notFound(post("/api/overrides/" + o.override() + "/cancel"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("несуществующий id отвечает так же, как чужой; свой объект по-прежнему доступен")
    void missingLooksLikeForeign() throws Exception {
        long missing = 987_654_321L;
        notFound(post("/settings/channels/" + missing + "/toggle"));
        notFound(get("/settings/widgets/" + missing));
        notFound(post("/pricing/competitors/" + missing + "/delete"));
        notFound(post("/bookings/pending/" + missing + "/confirm"));

        mvc.perform(get("/settings/widgets/" + mine.widget()).with(user(a))).andExpect(status().isOk());
        mvc.perform(post("/settings/channels/" + mine.channel() + "/toggle").with(user(a)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/pricing/competitors/" + mine.competitor() + "/delete").with(user(a)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT active FROM competitor_listings WHERE id = ?", Boolean.class,
                mine.competitor())).isFalse();
        // своя, но уже не ручная запись — обычное сообщение, не 404
        mvc.perform(post("/calendar/grid/action/delete").param("entry", "block:" + mine.channelBlock())
                        .with(user(a)).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
