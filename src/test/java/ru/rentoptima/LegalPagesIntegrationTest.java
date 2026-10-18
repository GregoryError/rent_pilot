package ru.rentoptima;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.entity.User;
import ru.rentoptima.legal.LegalDocuments;
import ru.rentoptima.legal.Operator;
import ru.rentoptima.legal.PersonalDataRetention;
import ru.rentoptima.security.TenantUserDetails;
import ru.rentoptima.service.PropertyCreator;
import ru.rentoptima.service.RegistrationService;
import ru.rentoptima.service.WidgetCreator;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Правовые документы (patches/INTEGRATION_LEGAL.md) на настоящей базе: адреса и редакции,
 * отсутствие заглушек, согласие гостя со сведениями хозяина, форма заявки без них, окно
 * повторного согласия, ссылки на документы отовсюду, сроки хранения. Нужен docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.uploads.dir=target/test-uploads"})
@AutoConfigureMockMvc
@DisplayName("Правовые документы: страницы, редакции, согласия")
class LegalPagesIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Квадратные скобки-подсказки юриста: [ЗАПОЛНИТЬ…], [ФИО], [email] и любые другие. */
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]{1,200}]");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PropertyCreator propertyCreator;
    @Autowired WidgetCreator widgetCreator;
    @Autowired RegistrationService registration;
    @Autowired PersonalDataRetention retention;
    @Autowired LegalDocuments documents;

    private TenantUserDetails owner;
    private Long tenantId;
    private Long unitTypeId;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Тест', ?) RETURNING id",
                Long.class, "legal-" + tag);
        owner = details(tenantId, insertUser(tenantId, tag + "@example.com", "Анна Хозяйкина", "OWNER"),
                tag + "@example.com", User.Role.OWNER);
        Long propertyId = propertyCreator.createProperty(tenantId, "Садовая 5", null, "Выборг");
        UnitType ut = propertyCreator.createUnitType(tenantId, propertyId, "Основной", 1, BigDecimal.valueOf(5000), null);
        unitTypeId = ut.getId();
    }

    private Long insertUser(Long tenant, String email, String displayName, String role) {
        return jdbc.queryForObject("INSERT INTO users(tenant_id, username, email, password_hash, display_name, role)"
                + " VALUES (?, ?, ?, 'x', ?, ?) RETURNING id", Long.class, tenant, email, email, displayName, role);
    }

    private static TenantUserDetails details(Long tenant, Long userId, String email, User.Role role) {
        Tenant t = new Tenant();
        t.setId(tenant);
        User u = new User();
        u.setId(userId);
        u.setTenant(t);
        u.setUsername(email);
        u.setPasswordHash("x");
        u.setDisplayName("Хозяин");
        u.setRole(role);
        return new TenantUserDetails(u);
    }

    private String html(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Видимый текст страницы без заглушек юриста, шаблонных меток и упоминания Telegram. */
    private static void assertClean(String path, String html) {
        String text = Jsoup.parse(html).text();
        assertThat(text).as(path).doesNotContain("ЗАПОЛНИТЬ", "ПРОВЕРИТЬ", "{{", "}}", "Telegram", "AI-чат");
        Matcher m = BRACKETS.matcher(text);
        assertThat(m.find()).as(path + ": " + (m.hitEnd() ? "" : text.substring(m.start(), Math.min(text.length(), m.end()))))
                .isFalse();
    }

    // --- Адреса и редакции

    @Test
    @DisplayName("каждый документ — 200 по своему адресу и по /v1 (noindex); прежние адреса работают")
    void addresses() throws Exception {
        for (String slug : LegalDocuments.SLUGS) {
            String page = html("/legal/" + slug);
            assertThat(Jsoup.parse(page).select("h1.legal-title").text()).as(slug).isNotBlank();
            assertThat(page).as(slug).contains("Редакция 1").contains(Operator.EMAIL);
            mvc.perform(get("/legal/" + slug + "/v1")).andExpect(status().isOk())
                    .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
        }
        mvc.perform(get("/legal/feedback-consent")).andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/legal/review"));
        mvc.perform(get("/legal/privacy/v9")).andExpect(status().isNotFound());
        mvc.perform(get("/legal/nope")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ни на одной странице /legal/* нет «[ЗАПОЛНИТЬ», «[ПРОВЕРИТЬ», скобок-заглушек и слова «Telegram»")
    void noPlaceholders() throws Exception {
        for (String slug : LegalDocuments.SLUGS) {
            assertClean("/legal/" + slug, html("/legal/" + slug));
            assertClean("/legal/" + slug + "/v1", html("/legal/" + slug + "/v1"));
        }
        BookingWidget w = widget();
        operator(w, "Иванов Иван Иванович", "host@example.com", "operator_name,operator_contact");
        assertClean("/legal/guest-consent/" + w.getSlug(), html("/legal/guest-consent/" + w.getSlug()));
    }

    @Test
    @DisplayName("Markdown: таблица — <table> с шапкой в блоке прокрутки, у длинного документа оглавление с якорями")
    void markdownRendered() throws Exception {
        Document doc = Jsoup.parse(html("/legal/privacy"));
        assertThat(doc.select(".legal-content .table-wrap > table thead th")).isNotEmpty();
        assertThat(doc.select(".legal-toc a")).hasSizeGreaterThanOrEqualTo(4);
        for (Element a : doc.select(".legal-toc a")) {
            assertThat(doc.getElementById(a.attr("href").substring(1))).as(a.attr("href")).isNotNull();
        }
        assertThat(doc.select(".legal-content a[href=/legal/terms]")).isNotEmpty();
    }

    @Test
    @DisplayName("новая редакция в админке не перезаписывает прежнюю: v2 действует, v1 — по своему адресу")
    void newVersionKeepsOld() throws Exception {
        Long adminId = insertUser(tenantId, "admin-" + tag + "@example.com", "Админ", "ADMIN");
        TenantUserDetails admin = details(tenantId, adminId, "admin-" + tag + "@example.com", User.Role.ADMIN);
        String v1 = documents.current("cookie-consent").orElseThrow().body();
        String list = mvc.perform(get("/admin/legal-pages").with(user(admin))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String slug : LegalDocuments.SLUGS) assertThat(list).contains("/admin/legal-pages/" + slug + "/edit");
        assertThat(list).doesNotContain("feedback-consent");
        mvc.perform(get("/admin/legal-pages/guest-consent/edit").with(user(admin))).andExpect(status().isOk());

        String preview = mvc.perform(post("/admin/legal-pages/preview").with(user(admin)).with(csrf())
                        .param("content", "| a | b |\n|---|---|\n| 1 | 2 |\n\n<script>alert(1)</script>"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(preview).contains("<table>").doesNotContain("<script>");

        mvc.perform(post("/admin/legal-pages/cookie-consent").with(user(admin)).with(csrf())
                        .param("title", "Согласие на cookie Яндекс Метрики")
                        .param("content", v1 + "\n\nДобавлен абзац второй редакции.")
                        .param("effectiveDate", "2026-12-01"))
                .andExpect(status().is3xxRedirection());

        assertThat(documents.currentVersion("cookie-consent")).isEqualTo(2);
        String current = html("/legal/cookie-consent");
        assertThat(current).contains("Редакция 2", "1 декабря 2026", "Добавлен абзац второй редакции", "/legal/cookie-consent/v1");
        String old = html("/legal/cookie-consent/v1");
        assertThat(old).contains("Редакция 1", "прежняя редакция").doesNotContain("Добавлен абзац второй редакции");
        assertThat(documents.version("cookie-consent", 1).orElseThrow().body()).isEqualTo(v1);
        // Владелец без роли ADMIN редактора не видит: раздела для него нет (AdminGateFilter)
        mvc.perform(get("/admin/legal-pages").with(user(owner))).andExpect(status().isNotFound());
    }

    // --- Согласие гостя и форма заявки

    private BookingWidget widget() {
        return widgetCreator.create(tenantId, unitTypeId, "Лофт " + tag);
    }

    private void operator(BookingWidget w, String name, String contact, String consentedFields) {
        jdbc.update("INSERT INTO guest_operators(tenant_id, name, contact) VALUES (?, ?, ?)"
                + " ON CONFLICT (tenant_id) DO UPDATE SET name = EXCLUDED.name, contact = EXCLUDED.contact",
                tenantId, name, contact);
        if (consentedFields != null) {
            jdbc.update("INSERT INTO public_data_consents(tenant_id, widget_id, fields, document_version)"
                    + " VALUES (?, ?, ?, 1)", tenantId, w.getId(), consentedFields);
        }
    }

    @Test
    @DisplayName("согласие гостя: данные хозяина — текстом; <script> и ](javascript:…) не исполняются и не становятся ссылкой")
    void guestConsentEscapesHostData() throws Exception {
        BookingWidget w = widget();
        String evil = "<script>alert(1)</script> [Иван](javascript:alert(1)) <img src=x onerror=alert(2)>";
        operator(w, evil, "host@example.com", "operator_name,operator_contact");

        MockHttpServletResponse r = mvc.perform(get("/legal/guest-consent/" + w.getSlug()))
                .andExpect(status().isOk()).andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andReturn().getResponse();
        Document doc = Jsoup.parse(r.getContentAsString(StandardCharsets.UTF_8));
        Element content = doc.selectFirst(".legal-content");
        assertThat(content.select("script, img, iframe")).isEmpty();
        for (Element e : content.getAllElements()) {
            for (Attribute a : e.attributes()) {
                assertThat(a.getKey()).doesNotStartWith("on");
                assertThat(a.getValue().toLowerCase()).doesNotContain("javascript:");
            }
        }
        assertThat(content.select("a").eachText()).doesNotContain("Иван");
        assertThat(content.text()).contains(evil, "host@example.com", "Обработчик по поручению оператора");
    }

    @Test
    @DisplayName("новая страница без сведений об операторе заявок не принимает; со сведениями и согласием — принимает")
    void newPageNeedsOperator() throws Exception {
        BookingWidget w = widget();
        String api = "/api/widget/" + w.getSlug() + "/booking";

        assertThat(mvc.perform(post(api).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("OPERATOR_MISSING");
        assertThat(html("/legal/guest-consent/" + w.getSlug())).contains("заявки на странице сейчас не принимаются");

        operator(w, "Иванов Иван Иванович", "host@example.com", null);   // сведения есть, согласия на показ нет
        mvc.perform(post(api).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());

        operator(w, "Иванов Иван Иванович", "host@example.com", "operator_name,operator_contact");
        // Дальше заявку останавливает уже проверка формы — значит, проверка оператора пройдена
        assertThat(mvc.perform(post(api).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("OPERATOR_MISSING");
        assertThat(html("/legal/guest-consent/" + w.getSlug())).contains("Иванов Иван Иванович", "host@example.com");

        String config = html("/api/widget/" + w.getSlug() + "/config");
        assertThat(config).contains("\"name\":\"Иванов Иван Иванович\"", "/legal/guest-consent/" + w.getSlug());
    }

    @Test
    @DisplayName("страница до V43: 30 дней заявки идут с запасным текстом (хост, e-mail OptiRent), потом — нет")
    void oldPageGrace() throws Exception {
        BookingWidget w = widget();
        String api = "/api/widget/" + w.getSlug() + "/booking";
        jdbc.update("UPDATE booking_widgets SET legal_grace_until = now() + interval '30 days' WHERE id = ?", w.getId());

        assertThat(mvc.perform(post(api).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("OPERATOR_MISSING");
        assertThat(Jsoup.parse(html("/legal/guest-consent/" + w.getSlug())).select(".legal-content").text())
                .contains("хост Анна Хозяйкина", Operator.EMAIL, "в течение 3 рабочих дней");
        // В кабинете — плашка с датой отключения
        assertThat(mvc.perform(get("/settings/widgets").with(user(owner))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).contains("legal-banner", "перестанет принимать заявки");

        jdbc.update("UPDATE booking_widgets SET legal_grace_until = now() - interval '1 minute' WHERE id = ?", w.getId());
        assertThat(mvc.perform(post(api).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("OPERATOR_MISSING");
    }

    @Test
    @DisplayName("контакты на странице — только после согласия на распространение; новый набор полей — снова согласие")
    void contactsOnlyWithConsent() throws Exception {
        BookingWidget w = widget();
        jdbc.update("UPDATE booking_widgets SET contact_phone = '+7 900 111-22-33' WHERE id = ?", w.getId());
        String config = "/api/widget/" + w.getSlug() + "/config";
        assertThat(html(config)).doesNotContain("111-22-33");

        // Сохранение блока «Что видят гости» с отметкой: согласие на телефон и сведения оператора
        mvc.perform(post("/settings/widgets/" + w.getId() + "/public-data").with(user(owner)).with(csrf())
                        .param("operatorName", "Иванов Иван Иванович").param("operatorContact", "host@example.com")
                        .param("consent", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(html(config)).contains("111-22-33");
        assertThat(jdbc.queryForObject("SELECT fields FROM public_data_consents WHERE widget_id = ? ORDER BY id DESC LIMIT 1",
                String.class, w.getId())).isEqualTo("phone,operator_name,operator_contact");

        // Добавили WhatsApp — он не виден, пока нет нового согласия; телефон виден
        jdbc.update("UPDATE booking_widgets SET contact_whatsapp = '+7 900 444-55-66' WHERE id = ?", w.getId());
        assertThat(html(config)).contains("111-22-33").doesNotContain("444-55-66");
        assertThat(mvc.perform(get("/settings/widgets/" + w.getId()).with(user(owner))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).contains("Новые поля гости не увидят");
        mvc.perform(post("/settings/widgets/" + w.getId() + "/public-data").with(user(owner)).with(csrf())
                        .param("operatorName", "Иванов Иван Иванович").param("operatorContact", "host@example.com")
                        .param("consent", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(html(config)).contains("444-55-66");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public_data_consents WHERE widget_id = ?", Integer.class,
                w.getId())).isEqualTo(2);

        // Без ФИО или контакта сведения не сохраняются; чужая страница — 404
        mvc.perform(post("/settings/widgets/" + w.getId() + "/public-data").with(user(owner)).with(csrf())
                        .param("operatorName", "").param("operatorContact", "x"))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT name FROM guest_operators WHERE tenant_id = ?", String.class, tenantId))
                .isEqualTo("Иванов Иван Иванович");
    }

    // --- Согласия пользователя кабинета

    @Test
    @DisplayName("регистрация сохраняет время и номера редакций; окна согласий у нового пользователя нет")
    void registrationStoresVersions() {
        User u = registration.register("new-" + tag + "@example.com", "Длинный-пароль-123", "Дом", true, true, false);
        Map<String, Object> row = jdbc.queryForMap("SELECT terms_version, privacy_version, consent_version,"
                + " agreed_to_terms_at, agreed_to_consent_at, legal_reconsent_required FROM users WHERE id = ?", u.getId());
        assertThat(row.get("terms_version")).isEqualTo(documents.currentVersion("terms"));
        assertThat(row.get("privacy_version")).isEqualTo(documents.currentVersion("privacy"));
        assertThat(row.get("consent_version")).isEqualTo(documents.currentVersion("consent"));
        assertThat(row.get("agreed_to_terms_at")).isNotNull();
        assertThat(row.get("agreed_to_consent_at")).isNotNull();
        assertThat(row.get("legal_reconsent_required")).isEqualTo(false);
    }

    @Test
    @DisplayName("пользователь до V42: кабинет закрыт окном с двумя чекбоксами; выход и документы открыты")
    void reconsent() throws Exception {
        jdbc.update("UPDATE users SET legal_reconsent_required = TRUE WHERE id = ?", owner.getUserId());

        mvc.perform(get("/dashboard").with(user(owner))).andExpect(redirectedUrl("/account/legal?next=/dashboard"));
        mvc.perform(get("/settings/widgets").with(user(owner))).andExpect(status().is3xxRedirection());
        mvc.perform(get("/legal/privacy").with(user(owner))).andExpect(status().isOk());
        String modal = mvc.perform(get("/account/legal").param("next", "/dashboard").with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(modal).contains("agreedToTerms", "agreedToConsent", "Мне больше 18 лет", "/logout",
                "mailto:" + Operator.EMAIL);

        // Один чекбокс — не пускает
        mvc.perform(post("/account/legal").with(user(owner)).with(csrf()).param("agreedToTerms", "on"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT legal_reconsent_required FROM users WHERE id = ?", Boolean.class,
                owner.getUserId())).isTrue();

        mvc.perform(post("/account/legal").with(user(owner)).with(csrf()).param("agreedToTerms", "on")
                        .param("agreedToConsent", "on").param("next", "/dashboard"))
                .andExpect(redirectedUrl("/dashboard"));
        Map<String, Object> row = jdbc.queryForMap("SELECT terms_version, consent_version, legal_reconsent_required"
                + " FROM users WHERE id = ?", owner.getUserId());
        assertThat(row.get("terms_version")).isEqualTo(documents.currentVersion("terms"));
        assertThat(row.get("consent_version")).isEqualTo(documents.currentVersion("consent"));
        assertThat(row.get("legal_reconsent_required")).isEqualTo(false);
        mvc.perform(get("/dashboard").with(user(owner))).andExpect(status().isOk());

        // Перенаправление только внутри сайта
        mvc.perform(post("/account/legal").with(user(owner)).with(csrf()).param("agreedToTerms", "on")
                        .param("agreedToConsent", "on").param("next", "//evil.example"))
                .andExpect(redirectedUrl("/calendar/grid"));
    }

    // --- Ссылки на документы

    @Test
    @DisplayName("ссылки на документы из подвала, регистрации, окна согласий, виджета, анкеты и баннера ведут на существующие страницы")
    void linksResolve() throws Exception {
        Set<String> links = new LinkedHashSet<>();
        links.addAll(legalLinks(html("/")));
        links.addAll(legalLinks(html("/register")));
        jdbc.update("UPDATE users SET legal_reconsent_required = TRUE WHERE id = ?", owner.getUserId());
        links.addAll(legalLinks(mvc.perform(get("/account/legal").with(user(owner))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)));
        jdbc.update("UPDATE users SET legal_reconsent_required = FALSE WHERE id = ?", owner.getUserId());

        BookingWidget w = widget();
        links.addAll(legalLinks(html("/api/widget/" + w.getSlug() + "/config")));
        String feedbackCode = jdbc.queryForObject("SELECT feedback_code FROM properties WHERE tenant_id = ?",
                String.class, tenantId);
        String housekeeperCode = jdbc.queryForObject("SELECT housekeeper_code FROM properties WHERE tenant_id = ?",
                String.class, tenantId);
        jdbc.update("UPDATE properties SET housekeeper_pin_hash = 'x' WHERE tenant_id = ?", tenantId);
        links.addAll(legalLinks(html("/feedback/" + feedbackCode)));
        links.addAll(legalLinks(html("/housekeeper/" + housekeeperCode)));
        for (String file : new String[]{"src/main/resources/static/js/consent.js", "widget/src/index.js",
                "src/main/resources/templates/pages/feedback/card.html"}) {
            links.addAll(legalLinks(Files.readString(Path.of(file))));
        }

        assertThat(links).contains("/legal/privacy", "/legal/terms", "/legal/consent", "/legal/cookies",
                "/legal/cookie-consent", "/legal/review", "/legal/guest-consent/" + w.getSlug());
        for (String link : links) {
            MockHttpServletResponse r = mvc.perform(get(link)).andReturn().getResponse();
            if (r.getStatus() == 301) r = mvc.perform(get(r.getHeader("Location"))).andReturn().getResponse();
            assertThat(r.getStatus()).as(link).isEqualTo(200);
        }
    }

    private static final Pattern LEGAL_LINK = Pattern.compile("/legal/[a-z-]+(?:/[a-z0-9-]+)?");

    private static Set<String> legalLinks(String text) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = LEGAL_LINK.matcher(text);
        while (m.find()) {
            String link = m.group();
            // «/legal/guest-consent/» + c.slug в исходнике виджета — без адреса страницы
            if (!link.endsWith("/")) found.add(link);
        }
        return found;
    }

    // --- Сроки хранения

    @Test
    @DisplayName("автоочистка: через 12 месяцев после выезда у заявки стираются имя, телефон, e-mail и комментарий")
    void guestContactsRetention() {
        Long propertyId = jdbc.queryForObject("SELECT id FROM properties WHERE tenant_id = ?", Long.class, tenantId);
        String insert = "INSERT INTO bookings(tenant_id, property_id, unit_type_id, check_in, check_out, nights, amount,"
                + " status, source, data_source, guest_name, guest_phone, guest_email, notes, guest_count)"
                + " VALUES (?, ?, ?, current_date - ?::int, current_date - ?::int, 3, 15000, 'BOOKED', 'DIRECT_WIDGET',"
                + " 'WIDGET', 'Ж.', '+79001234567', 'g@example.com', 'Комментарий: поздно', 2) RETURNING id";
        Long old = jdbc.queryForObject(insert, Long.class, tenantId, propertyId, unitTypeId, 400, 397);
        Long recent = jdbc.queryForObject(insert, Long.class, tenantId, propertyId, unitTypeId, 340, 337);

        assertThat(retention.clearGuestContacts()).isGreaterThanOrEqualTo(1);

        Map<String, Object> cleared = jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", old);
        assertThat(cleared.get("guest_name")).isNull();
        assertThat(cleared.get("guest_phone")).isNull();
        assertThat(cleared.get("guest_email")).isNull();
        assertThat(cleared.get("notes")).isNull();
        assertThat(cleared.get("guest_count")).isEqualTo(2);
        assertThat(cleared.get("check_in")).isNotNull();
        Map<String, Object> kept = jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", recent);
        assertThat(kept.get("guest_phone")).isEqualTo("+79001234567");
        assertThat(kept.get("notes")).isEqualTo("Комментарий: поздно");
    }

    @Test
    @DisplayName("истёкшие ссылки сброса пароля и подтверждения почты удаляются, действующие — остаются")
    void expiredTokensPurged() {
        jdbc.update("INSERT INTO auth_tokens(user_id, kind, token_hash, expires_at) VALUES"
                + " (?, 'RESET', ?, now() - interval '1 minute'), (?, 'VERIFY', ?, now() + interval '7 days')",
                owner.getUserId(), "old-" + tag, owner.getUserId(), "new-" + tag);

        assertThat(retention.purgeExpiredTokens()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForList("SELECT btrim(token_hash) FROM auth_tokens WHERE user_id = ?", String.class,
                owner.getUserId())).containsExactly("new-" + tag);
    }
}
