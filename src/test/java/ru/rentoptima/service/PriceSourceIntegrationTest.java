package ru.rentoptima.service;

import org.junit.jupiter.api.BeforeEach;
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
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.TenantUserDetails;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Один источник цены: автопилот, рекомендации, ценовой календарь, AI-советник и AI-чат считают от
 * цены жилья ({@link EffectivePriceService}). Настройки {@code weekday_base_price} /
 * {@code weekend_base_price} у тенанта заданы заведомо другими (3000 / 4000) — их нигде быть не
 * должно. Плюс проверка исходников: эти ключи читаются только подсказкой для нового жилья.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.uploads.dir=target/test-uploads"})
@AutoConfigureMockMvc
@DisplayName("Цена дня — только цена жилья: автопилот, /calendar, AI-советник, AI-чат")
class PriceSourceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PropertyCreator propertyCreator;
    @Autowired PropertyRepository propertyRepo;
    @Autowired PricingEngine engine;
    @Autowired AiPricingAdvisor advisor;
    @Autowired AiChatService chat;

    private TenantUserDetails owner;
    private Property property;

    @BeforeEach
    void apartmentWithMisleadingSettings() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Цены', ?) RETURNING id",
                Long.class, "price-" + tag);
        jdbc.update("INSERT INTO system_settings(tenant_id, key, value) VALUES (?, 'weekday_base_price', '3000'),"
                + " (?, 'weekend_base_price', '4000'), (?, 'learning_enabled', 'true'), (?, 'city', 'Выборг')",
                tenantId, tenantId, tenantId, tenantId);
        Long userId = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, display_name, role)"
                + " VALUES (?, ?, 'x', 'Хозяин', 'OWNER') RETURNING id", Long.class, tenantId, tag + "@example.com");
        Long propertyId = propertyCreator.createProperty(tenantId, "Студия", null, "Казань");
        propertyCreator.createUnitType(tenantId, propertyId, "Основной", 1, BigDecimal.valueOf(4500), BigDecimal.valueOf(6000));
        property = propertyRepo.findById(propertyId).orElseThrow();
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
    }

    private static LocalDate nextFriday() {
        return LocalDate.now().plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
    }

    @Test
    @DisplayName("автопилот записывает в журнал решения от цены жилья, а не от настроек")
    void autopilotUsesApartmentPrice() {
        List<PricingEngine.PricingRecommendation> recs = engine.calculateRecommendations(
                property, LocalDate.now(), LocalDate.now().plusDays(30));
        assertThat(recs).isNotEmpty().allMatch(r -> List.of(4500, 6000).contains(r.currentPrice().intValue()));

        engine.runForProperty(property, "FULL");
        Map<LocalDate, Integer> decided = jdbc.queryForList(
                        "SELECT target_date, price FROM pricing_decisions WHERE property_id = ?", property.getId()).stream()
                .collect(Collectors.toMap(r -> ((java.sql.Date) r.get("target_date")).toLocalDate(),
                        r -> ((Number) r.get("price")).intValue()));
        assertThat(decided).isNotEmpty();
        for (PricingEngine.PricingRecommendation r : recs) {
            if (decided.containsKey(r.date())) {
                assertThat(decided.get(r.date())).as(r.date().toString()).isEqualTo(r.recommendedPrice().intValue());
            }
        }
    }

    @Test
    @DisplayName("/calendar показывает цену жилья: 6000 в ночи пт и сб, 4500 в остальные")
    void calendarUsesApartmentPrice() throws Exception {
        LocalDate friday = nextFriday();
        String html = mvc.perform(get("/calendar").param("year", String.valueOf(friday.getYear()))
                        .param("month", String.valueOf(friday.getMonthValue())).with(user(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("4500 ₽").doesNotContain(">3000 ₽<").doesNotContain(">4000 ₽<");
    }

    @Test
    @DisplayName("подсказка AI-советнику: цена и город жилья, не настройки")
    void advisorPromptUsesApartmentPrice() {
        List<PricingEngine.PricingRecommendation> recs = engine.calculateRecommendations(
                property, LocalDate.now(), LocalDate.now().plusDays(14));
        String prompt = advisor.buildPrompt(property, recs, property.getTenantId(), null);
        assertThat(prompt).contains("будни 4500₽", "выходные (ночи с пт и с сб) 6000₽", "город: Казань")
                .doesNotContain("3000₽").doesNotContain("4000₽").doesNotContain("Выборг");
    }

    @Test
    @DisplayName("подсказка AI-советнику обезличена: нет названия и адреса объекта, имени и e-mail хозяина, данных гостей")
    void advisorPromptHasNoPersonalData() {
        jdbc.update("UPDATE properties SET name = 'Квартира Галиевых на Баумана', address = 'ул. Баумана, д. 12, кв. 5'"
                + " WHERE id = ?", property.getId());
        jdbc.update("UPDATE users SET display_name = 'Тимур Галиев' WHERE tenant_id = ?", property.getTenantId());
        jdbc.update("INSERT INTO bookings(tenant_id, property_id, check_in, check_out, nights, amount, status, source,"
                        + " guest_name, guest_phone, notes) VALUES (?, ?, current_date - 5, current_date - 2, 3, 13500,"
                        + " 'BOOKED', 'AVITO', 'Ж.', '+79001234567', 'Комментарий: паспорт 4010')",
                property.getTenantId(), property.getId());
        // Отзыв с оценкой и текстом: раньше текст отзывов уходил в подсказку
        Long scale = jdbc.queryForObject("INSERT INTO feedback_questions(tenant_id, question_text, question_type,"
                + " sort_order, active) VALUES (?, 'Общая оценка', 'SCALE', 1, true) RETURNING id", Long.class,
                property.getTenantId());
        Long text = jdbc.queryForObject("INSERT INTO feedback_questions(tenant_id, question_text, question_type,"
                + " sort_order, active) VALUES (?, 'Комментарий', 'TEXT', 2, true) RETURNING id", Long.class,
                property.getTenantId());
        Long response = jdbc.queryForObject("INSERT INTO feedback_responses(property_id, session_id, guest_name,"
                + " completed) VALUES (?, ?, 'Ж.', true) RETURNING id", Long.class, property.getId(),
                "s-" + UUID.randomUUID());
        jdbc.update("INSERT INTO feedback_answers(response_id, question_id, numeric_value) VALUES (?, ?, 9)", response, scale);
        jdbc.update("INSERT INTO feedback_answers(response_id, question_id, text_value) VALUES (?, ?,"
                + " 'Хозяйка Гульнара очень милая')", response, text);
        Property fresh = propertyRepo.findById(property.getId()).orElseThrow();
        List<PricingEngine.PricingRecommendation> recs = engine.calculateRecommendations(
                fresh, LocalDate.now(), LocalDate.now().plusDays(14));

        String prompt = advisor.buildPrompt(fresh, recs, fresh.getTenantId(), null);

        assertThat(prompt).contains("город: Казань", "Тип жилья: Квартира", "единиц в категории: 1", "4500 ₽/ночь")
                .doesNotContain("Галиев", "Баумана", "кв. 5", owner.getUsername(), "@example.com",
                        "Ж.", "+7900", "паспорт", "AVITO", "Гульнара", "ОТЗЫВЫ");
    }

    @Test
    @DisplayName("подсказка AI-чату: цена и город жилья, цена дня как в шахматке")
    void chatPromptUsesApartmentPrice() {
        String prompt = chat.buildSystemPrompt(property.getTenantId());
        assertThat(prompt).contains("Цена квартиры в будни: 4500 ₽", "ночи с пт и с сб): 6000 ₽", "Город: Казань")
                .doesNotContain("3000 ₽").doesNotContain("4000 ₽").doesNotContain("Выборг");
        String friday = nextFriday().toString();
        assertThat(prompt).containsPattern(friday + " Пт свободно 6000₽");
    }

    @Test
    @DisplayName("исходники: weekday/weekend_base_price читаются только как подсказка для нового жилья")
    void settingsKeysAreOnlyDefaults() throws IOException {
        List<String> allowed = List.of("ApartmentDefaults.java", "SettingsController.java", "RegistrationService.java");
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<String> offenders = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !allowed.contains(f.getFileName().toString()))
                    .filter(f -> {
                        try {
                            String src = Files.readString(f);
                            return src.contains("weekday_base_price") || src.contains("weekend_base_price");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(Path::toString).toList();
            assertThat(offenders).isEmpty();
        }
        // ApartmentDefaults — только в формах нового жилья
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            List<String> users = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> {
                        try {
                            return Files.readString(f).contains("ApartmentDefaults")
                                    && !f.getFileName().toString().equals("ApartmentDefaults.java");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(f -> f.getFileName().toString()).sorted().toList();
            assertThat(users).containsExactly("OnboardingController.java", "PropertyController.java");
        }
    }
}
