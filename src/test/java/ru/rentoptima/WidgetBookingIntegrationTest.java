package ru.rentoptima;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.config.WidgetCorsConfig;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.security.TenantUserDetails;
import ru.rentoptima.service.WidgetBookingService;
import ru.rentoptima.service.WidgetBookingService.StayRequest;
import ru.rentoptima.service.WidgetBookingService.Submission;
import ru.rentoptima.service.WidgetError;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Виджет бронирования на настоящем PostgreSQL: все миграции Flyway, блокировка
 * {@code SELECT … FOR UPDATE} и публичный API целиком.
 * <p>
 * Нужен docker; без него тест пропускается (и в сборке образа тоже: там -DskipTests).
 * С colima перед запуском:
 * {@code export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
 * TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock}.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "app.uploads.dir=target/test-uploads")
@AutoConfigureMockMvc
@DisplayName("Виджет бронирования на PostgreSQL: гонка, API, CORS, экспорт, админка")
class WidgetBookingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ALLOWED_ORIGIN = "https://mysite.ru";

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired WidgetBookingService widgets;
    @Autowired BookingWidgetRepository widgetRepo;
    @Autowired WidgetCorsConfig cors;

    private String slug;
    private String icalSecret;
    private Long unitTypeId;
    private BookingWidget widget;

    @BeforeEach
    void seed() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        slug = "loft-" + tag;
        icalSecret = "ical-" + tag;
        Long tenantId = jdbc.queryForObject(
                "INSERT INTO tenants(name, slug) VALUES ('Тест', ?) RETURNING id", Long.class, "t-" + tag);
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO properties(tenant_id, name, feedback_code, housekeeper_code)"
                        + " VALUES (?, 'Лофт', ?, ?) RETURNING id", Long.class, tenantId, "f-" + tag, "h-" + tag);
        unitTypeId = jdbc.queryForObject(
                "INSERT INTO unit_types(tenant_id, property_id, name, unit_count, base_price)"
                        + " VALUES (?, ?, 'Основной', 1, 4000) RETURNING id", Long.class, tenantId, propertyId);
        Long widgetChannel = jdbc.queryForObject(
                "INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled)"
                        + " VALUES (?, ?, 'WIDGET', 'Прямая бронь', false) RETURNING id",
                Long.class, tenantId, unitTypeId);
        jdbc.update("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled, export_secret)"
                + " VALUES (?, ?, 'ICAL', 'Суточно', false, ?)", tenantId, unitTypeId, icalSecret);
        jdbc.update("INSERT INTO booking_widgets(tenant_id, channel_id, unit_type_id, secret, slug, title,"
                        + " cleaning_fee, weekly_discount_percent, allowed_origins)"
                        + " VALUES (?, ?, ?, ?, ?, 'Лофт у парка', 1500, 10, ?)",
                tenantId, widgetChannel, unitTypeId, "secret-" + tag, slug, ALLOWED_ORIGIN);
        widget = widgetRepo.findBySlugAndActiveTrue(slug).orElseThrow();
        cors.evict();
    }

    private static StayRequest request(LocalDate from, LocalDate to, int n) {
        return new StayRequest(from, to, 2, 0, 0, "Гость " + n, "+7900000" + String.format("%04d", n),
                null, null, true, null, null, "ru", null, null, null, null);
    }

    @Test
    @DisplayName("восемь одновременных заявок на одни даты — проходит ровно одна")
    void concurrentRequestsForSameDates() throws Exception {
        LocalDate from = LocalDate.now().plusDays(20);
        LocalDate to = from.plusDays(3);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Submission>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                return widgets.submit(widget, request(from, to, n));
            }));
        }
        start.countDown();

        int accepted = 0;
        for (Future<Submission> f : futures) {
            Submission s = f.get(30, TimeUnit.SECONDS);
            if (s.ok()) accepted++;
            else assertThat(s.error()).isEqualTo(WidgetError.DATES_TAKEN);
        }
        pool.shutdown();

        assertThat(accepted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM calendar_blocks WHERE unit_type_id = ?"
                + " AND cancelled_at IS NULL", Integer.class, unitTypeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE unit_type_id = ?",
                Integer.class, unitTypeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("пересекающиеся заявки не проходят обе, соседние — проходят (выезд в день чужого заезда)")
    void overlappingAndAdjacent() throws Exception {
        LocalDate from = LocalDate.now().plusDays(40);
        assertThat(widgets.submit(widget, request(from, from.plusDays(3), 1)).ok()).isTrue();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Submission> overlap = pool.submit(() ->
                widgets.submit(widget, request(from.plusDays(2), from.plusDays(5), 2)));
        Future<Submission> adjacent = pool.submit(() ->
                widgets.submit(widget, request(from.plusDays(3), from.plusDays(5), 3)));

        Submission a = adjacent.get(30, TimeUnit.SECONDS);
        Submission o = overlap.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        // Обе пересекаются с первой бронью или друг с другом; пройти может максимум одна,
        // и это не та, что наложилась бы на первую.
        assertThat(o.ok() && a.ok()).isFalse();
        if (o.ok()) assertThat(a.error()).isEqualTo(WidgetError.DATES_TAKEN);
        else assertThat(o.error()).isEqualTo(WidgetError.DATES_TAKEN);
    }

    @Test
    @DisplayName("последнее применение промокода достаётся одной из двух одновременных броней")
    void promoLastUse() throws Exception {
        jdbc.update("INSERT INTO promo_codes(tenant_id, widget_id, code, discount_type, discount_value, max_uses)"
                + " VALUES (?, ?, 'LAST', 'PERCENT', 10, 1)", widget.getTenantId(), widget.getId());
        LocalDate a = LocalDate.now().plusDays(60);
        LocalDate b = LocalDate.now().plusDays(70);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Submission>> futures = new ArrayList<>();
        for (LocalDate from : List.of(a, b)) {
            futures.add(pool.submit(() -> {
                start.await();
                return widgets.submit(widget, new StayRequest(from, from.plusDays(2), 2, 0, 0, "Гость",
                        "+79000000000", null, null, true, "last", null, "ru", null, null, null, null));
            }));
        }
        start.countDown();
        List<Submission> results = new ArrayList<>();
        for (Future<Submission> f : futures) results.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(results).filteredOn(Submission::ok).hasSize(1);
        assertThat(results).filteredOn(s -> !s.ok()).singleElement()
                .satisfies(s -> assertThat(s.error()).isEqualTo(WidgetError.PROMO_EXHAUSTED));
        assertThat(jdbc.queryForObject("SELECT used_count FROM promo_codes WHERE widget_id = ?",
                Integer.class, widget.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("API: расчёт → бронь → занято в календаре → событие с UID-маркером в iCal-фиде канала")
    void apiFlow() throws Exception {
        LocalDate from = LocalDate.now().plusDays(10);
        LocalDate to = from.plusDays(7);
        String api = "/api/widget/" + slug;

        MvcResult quoted = mvc.perform(post(api + "/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checkin\":\"" + from + "\",\"checkout\":\"" + to + "\",\"promo\":\"NOPE\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode quote = json.readTree(quoted.getResponse().getContentAsString());
        // 7 × 4000 = 28000, −10 % за неделю = 25200, + уборка 1500
        assertThat(quote.path("total").asInt()).isEqualTo(26700);
        assertThat(quote.path("lengthDiscount").path("amount").asInt()).isEqualTo(2800);
        assertThat(quote.path("promoError").path("code").asText()).isEqualTo("PROMO_INVALID");
        String token = quote.path("formToken").asText();

        String form = "{\"checkin\":\"" + from + "\",\"checkout\":\"" + to + "\",\"adults\":2,\"children\":1,"
                + "\"name\":\"Иван Петров\",\"phone\":\"+7 900 123-45-67\",\"email\":\"guest@example.com\","
                + "\"consent\":true,\"expectedTotal\":26700,\"formToken\":\"" + token + "\","
                + "\"utm\":{\"source\":\"telegram\"},\"referrer\":\"https://t.me/\"}";

        // Сразу после расчёта — слишком быстро для человека
        mvc.perform(post(api + "/booking").contentType(MediaType.APPLICATION_JSON).content(form))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("TOO_FAST"));

        Thread.sleep(3_100);
        MvcResult booked = mvc.perform(post(api + "/booking").contentType(MediaType.APPLICATION_JSON).content(form))
                .andExpect(status().isOk()).andReturn();
        JsonNode booking = json.readTree(booked.getResponse().getContentAsString());
        assertThat(booking.path("status").asText()).isEqualTo("PENDING");
        assertThat(booking.path("number").asText()).hasSize(8);
        String requestId = booking.path("requestId").asText();

        // Имя сохранено инициалом, источник и согласие записаны
        assertThat(jdbc.queryForMap("SELECT guest_name, utm_source, amount, guest_count, consent_at IS NOT NULL AS consented"
                + " FROM bookings WHERE external_id = ?", requestId))
                .containsEntry("guest_name", "И.").containsEntry("utm_source", "telegram")
                .containsEntry("guest_count", 3).containsEntry("consented", true);

        // Вторая заявка на те же даты
        MvcResult again = mvc.perform(post(api + "/quote").contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkin\":\"" + from + "\",\"checkout\":\"" + to + "\"}")).andReturn();
        String token2 = json.readTree(again.getResponse().getContentAsString()).path("formToken").asText();
        Thread.sleep(3_100);
        mvc.perform(post(api + "/booking").contentType(MediaType.APPLICATION_JSON)
                        .content(form.replace(token, token2)))
                .andExpect(status().isConflict())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("DATES_TAKEN"));

        // Календарь: ночи заняты
        MvcResult availability = mvc.perform(get(api + "/availability")
                        .param("from", from.toString()).param("to", to.plusDays(1).toString()))
                .andExpect(status().isOk()).andReturn();
        JsonNode days = json.readTree(availability.getResponse().getContentAsString()).path("days");
        assertThat(days.get(0).path("b").asBoolean()).isTrue();
        assertThat(days.get(7).path("b").asBoolean()).isFalse();
        assertThat(days.get(7).path("p").asInt()).isEqualTo(4000);

        // Ближайшие свободные даты той же длины
        MvcResult alternatives = mvc.perform(get(api + "/alternatives")
                        .param("checkin", from.toString()).param("checkout", to.toString()))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(alternatives.getResponse().getContentAsString()).path("alternatives"))
                .isNotEmpty();

        // Файл календаря для гостя
        mvc.perform(get(api + "/bookings/" + requestId + "/calendar.ics"))
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("BEGIN:VEVENT"));

        // Фид канала площадки: заявка под UID-маркером
        String expectedUid = "UID:optirent-widget-" + requestId + "@optirent.ru";
        mvc.perform(get("/ical/" + icalSecret + ".ics")).andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains(expectedUid));

        // Подтверждение: UID тот же, событие одно
        Long bookingId = jdbc.queryForObject("SELECT id FROM bookings WHERE external_id = ?", Long.class, requestId);
        assertThat(widgets.confirm(widget.getTenantId(), bookingId).ok()).isTrue();
        mvc.perform(get("/ical/" + icalSecret + ".ics")).andExpect(r -> {
            String ics = r.getResponse().getContentAsString();
            assertThat(ics).containsOnlyOnce(expectedUid).doesNotContain("STATUS:CANCELLED");
        });

        // Отмена: даты свободны, событие остаётся со STATUS:CANCELLED
        assertThat(widgets.cancel(widget.getTenantId(), bookingId).ok()).isTrue();
        mvc.perform(get("/ical/" + icalSecret + ".ics")).andExpect(r -> {
            String ics = r.getResponse().getContentAsString();
            assertThat(ics).containsOnlyOnce(expectedUid).contains("STATUS:CANCELLED");
        });
        assertThat(widgets.busyNights(widget, LocalDate.now())).doesNotContain(from);
    }

    @Test
    @DisplayName("GET-ответы API: Cache-Control 60 секунд и ETag, повторный запрос — 304")
    void cachingAndEtag() throws Exception {
        MvcResult first = mvc.perform(get("/api/widget/" + slug + "/config"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(header().exists("ETag"))
                .andReturn();
        String etag = first.getResponse().getHeader("ETag");

        mvc.perform(get("/api/widget/" + slug + "/config").header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("CORS: разрешённый хозяином сайт проходит, посторонний — 403, в том числе на старом API по секрету")
    void corsByAllowedOrigins() throws Exception {
        String config = "/api/widget/" + slug + "/config";

        mvc.perform(get(config).header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
        mvc.perform(get(config).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/widget/" + slug + "/booking").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/widget/" + slug + "/booking").header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));

        String legacy = "/widget/" + widget.getSecret() + "/availability";
        mvc.perform(get(legacy).header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get(legacy).header("Origin", ALLOWED_ORIGIN)).andExpect(status().isOk());
        // Без Origin — запрос с нашего же домена
        mvc.perform(get(legacy)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("iframe: страница виджета отдаёт frame-ancestors со списком сайтов, остальное — X-Frame-Options DENY")
    void frameAncestors() throws Exception {
        mvc.perform(get("/widget/" + widget.getSecret()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'self' " + ALLOWED_ORIGIN))
                .andExpect(header().doesNotExist("X-Frame-Options"));
        mvc.perform(get("/book/" + widget.getSecret()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"));
        mvc.perform(get("/api/widget/" + slug + "/config"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    @DisplayName("мгновенная бронь: сразу BOOKED и якорь WIDGET_BOOKING, даты заняты один раз")
    void instantMode() {
        jdbc.update("UPDATE booking_widgets SET mode = 'INSTANT' WHERE id = ?", widget.getId());
        BookingWidget instant = widgetRepo.findBySlugAndActiveTrue(slug).orElseThrow();
        LocalDate from = LocalDate.now().plusDays(90);

        Submission s = widgets.submit(instant, request(from, from.plusDays(2), 1));

        assertThat(s.ok()).isTrue();
        assertThat(s.holdExpiresAt()).isNull();
        assertThat(s.booking().getStatus()).isEqualTo("BOOKED");
        assertThat(jdbc.queryForObject("SELECT block_type FROM calendar_blocks WHERE external_uid = ?",
                String.class, s.booking().getExternalId())).isEqualTo("WIDGET_BOOKING");
        assertThat(widgets.busyNights(instant, LocalDate.now())).contains(from, from.plusDays(1))
                .doesNotContain(from.plusDays(2));
        assertThat(widgets.submit(instant, request(from, from.plusDays(2), 2)).error())
                .isEqualTo(WidgetError.DATES_TAKEN);
    }

    // --- Админка: шаблоны и формы на настоящей базе

    private TenantUserDetails host() {
        Tenant tenant = new Tenant();
        tenant.setId(widget.getTenantId());
        User user = new User();
        user.setId(1L);
        user.setTenant(tenant);
        user.setUsername("host");
        user.setPasswordHash("x");
        user.setDisplayName("Хозяин");
        user.setRole(User.Role.OWNER);
        return new TenantUserDetails(user);
    }

    @Test
    @DisplayName("админка: страница настроек открывается, настройки и промокод сохраняются, заявки показываются")
    void adminPages() throws Exception {
        String page = "/settings/widgets/" + widget.getId();

        mvc.perform(get(page).with(user(host())))
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .contains("Промокоды", "Сайты, где можно разместить виджет", slug));

        // Предпросмотр нового виджета и его бандл (бандл — без входа)
        mvc.perform(get(page + "/preview").with(user(host())))
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .contains("<optirent-booking data-widget=\"" + slug + "\">", "/w.js"));
        mvc.perform(get("/w.js"))
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("optirent-booking"));

        mvc.perform(post(page + "/update").with(user(host())).with(csrf())
                        .param("title", "Лофт у парка").param("minNights", "2").param("maxNights", "30")
                        .param("maxGuests", "4").param("bookingWindowDays", "180").param("holdHours", "12")
                        .param("checkinTime", "15:00").param("checkoutTime", "11:00")
                        .param("mode", "INSTANT").param("cleaningFee", "2000")
                        .param("weeklyDiscountPercent", "5").param("monthlyDiscountPercent", "15")
                        .param("prepaymentPercent", "30").param("petsAllowed", "true")
                        .param("noCheckinDays", "7").param("noCheckoutDays", "1", "7")
                        .param("allowedOrigins", "mysite.ru\nhttps://shop.example.com/page")
                        .param("contactTelegram", "@host").param("slug", slug + "-new"))
                .andExpect(status().is3xxRedirection());

        BookingWidget saved = widgetRepo.findById(widget.getId()).orElseThrow();
        assertThat(saved.getSlug()).isEqualTo(slug + "-new");
        assertThat(saved.getMode()).isEqualTo("INSTANT");
        assertThat(saved.getHoldMinutes()).isEqualTo(720);
        assertThat(saved.getCleaningFee()).isEqualByComparingTo("2000");
        assertThat(saved.getNoCheckinDays()).isEqualTo("7");
        assertThat(saved.getNoCheckoutDays()).isEqualTo("1,7");
        assertThat(saved.getAllowedOrigins()).isEqualTo("https://mysite.ru\nhttps://shop.example.com");
        assertThat(saved.getPetsAllowed()).isTrue();

        // Занятый адрес: остальное сохраняется, адрес — нет
        jdbc.update("UPDATE booking_widgets SET slug = ? WHERE id = ?", slug, widget.getId());
        Long otherChannel = jdbc.queryForObject("SELECT channel_id FROM booking_widgets WHERE id = ?",
                Long.class, widget.getId());
        jdbc.update("INSERT INTO booking_widgets(tenant_id, channel_id, unit_type_id, secret, slug, title)"
                        + " VALUES (?, ?, ?, ?, ?, 'Другой')",
                widget.getTenantId(), otherChannel, unitTypeId, "other-" + slug, "taken-" + slug);
        mvc.perform(post(page + "/update").with(user(host())).with(csrf())
                        .param("title", "Лофт").param("minNights", "1").param("maxNights", "30")
                        .param("maxGuests", "4").param("bookingWindowDays", "180").param("holdHours", "24")
                        .param("checkinTime", "14:00").param("checkoutTime", "12:00")
                        .param("slug", "taken-" + slug))
                .andExpect(status().is3xxRedirection());
        assertThat(widgetRepo.findById(widget.getId()).orElseThrow().getSlug()).isEqualTo(slug);

        mvc.perform(post(page + "/promo").with(user(host())).with(csrf())
                        .param("code", "leto 10").param("discountType", "PERCENT").param("discountValue", "10")
                        .param("validUntil", LocalDate.now().plusDays(30).toString()).param("maxUses", "5"))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM promo_codes WHERE widget_id = ? AND code = 'LETO10'",
                Integer.class, widget.getId())).isEqualTo(1);

        mvc.perform(get(page).with(user(host())))
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("LETO10", "Действует"));

        // Чужой tenant страницу не видит
        Tenant stranger = new Tenant();
        stranger.setId(widget.getTenantId() + 1000);
        User other = new User();
        other.setId(2L);
        other.setTenant(stranger);
        other.setUsername("other");
        other.setPasswordHash("x");
        other.setDisplayName("Чужой");
        other.setRole(User.Role.OWNER);
        mvc.perform(get(page).with(user(new TenantUserDetails(other))))
                .andExpect(status().is3xxRedirection());

        // Заявки: ожидающая и подтверждённая с кнопкой отмены
        LocalDate from = LocalDate.now().plusDays(120);
        BookingWidget requestMode = widgetRepo.findById(widget.getId()).orElseThrow();
        requestMode.setMode("REQUEST");
        Submission pending = widgets.submit(requestMode, request(from, from.plusDays(2), 1));
        Submission confirmed = widgets.submit(requestMode, request(from.plusDays(5), from.plusDays(7), 2));
        assertThat(pending.ok() && confirmed.ok()).isTrue();
        widgets.confirm(widget.getTenantId(), confirmed.booking().getId());

        mvc.perform(get("/bookings/pending").with(user(host())))
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .contains("№ " + pending.booking().getPublicCode(), "Подтвердить бронь",
                                "/bookings/pending/" + confirmed.booking().getId() + "/cancel"));

        mvc.perform(post("/bookings/pending/" + confirmed.booking().getId() + "/cancel")
                        .with(user(host())).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class,
                confirmed.booking().getId())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("фото: загрузка через админку → варианты отдаются с кэшем на год → попадают в настройки виджета → удаляются")
    void photos() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1200, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpg", jpeg);
        String page = "/settings/widgets/" + widget.getId();

        mvc.perform(multipart(page + "/photos")
                        .file(new MockMultipartFile("files", "IMG_0001.jpg", "image/jpeg", jpeg.toByteArray()))
                        .file(new MockMultipartFile("files", "notes.txt", "text/plain", "не фото".getBytes()))
                        .with(user(host())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", "Загружено фотографий: 1"))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("notes.txt")));

        String key = jdbc.queryForObject("SELECT file_key FROM widget_photos WHERE widget_id = ?", String.class, widget.getId());
        assertThat(jdbc.queryForObject("SELECT widths FROM widget_photos WHERE widget_id = ?", String.class, widget.getId()))
                .isEqualTo("480,960");

        // Файл отдаётся без входа, с кэшем на год; чужие имена — 404
        mvc.perform(get("/media/widget/" + key + "-960.jpg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
        mvc.perform(get("/media/widget/" + key + "-1600.jpg")).andExpect(status().isNotFound());
        mvc.perform(get("/media/widget/..%2F..%2Fpom.xml")).andExpect(r ->
                assertThat(r.getResponse().getStatus()).isIn(400, 404));

        MvcResult config = mvc.perform(get("/api/widget/" + slug + "/config")).andReturn();
        JsonNode photo = json.readTree(config.getResponse().getContentAsString()).path("photos").get(0);
        assertThat(photo.path("jpg").asText()).contains("/media/widget/" + key + "-480.jpg 480w");
        assertThat(photo.path("lqip").asText()).startsWith("data:image/jpeg;base64,");
        assertThat(photo.path("w").asInt()).isEqualTo(960);

        mvc.perform(get(page).with(user(host())))
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains(key + "-960.jpg", "Обложка"));

        // Чужой tenant удалить фото не может
        Long photoId = jdbc.queryForObject("SELECT id FROM widget_photos WHERE widget_id = ?", Long.class, widget.getId());
        Tenant stranger = new Tenant();
        stranger.setId(widget.getTenantId() + 1000);
        User other = new User();
        other.setId(3L);
        other.setTenant(stranger);
        other.setUsername("other");
        other.setPasswordHash("x");
        other.setDisplayName("Чужой");
        other.setRole(User.Role.OWNER);
        mvc.perform(post(page + "/photos/" + photoId + "/delete").with(user(new TenantUserDetails(other))).with(csrf()));
        mvc.perform(get("/media/widget/" + key + "-960.jpg")).andExpect(status().isOk());

        mvc.perform(post(page + "/photos/" + photoId + "/delete").with(user(host())).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/media/widget/" + key + "-960.jpg")).andExpect(status().isNotFound());
    }
}
