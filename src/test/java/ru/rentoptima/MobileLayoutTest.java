package ru.rentoptima;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Вёрстка на узких экранах: ни одна страница не шире экрана на 320–1280 px, в шахматке
 * день не уже 44 px и открывается тапом. Сам обход — {@code tools/mobile-check/check.mjs}
 * (headless Chrome), здесь поднимается приложение с тестовыми данными и запускается скрипт.
 * <p>
 * Нужны docker (как {@link WidgetBookingIntegrationTest}), node, Chrome и один раз
 * {@code cd tools/mobile-check && npm install}. Без любого из них тест пропускается.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.uploads.dir=target/test-uploads", "app.widget.requests-per-minute=100000"})
@DisplayName("Вёрстка на узких экранах: нет горизонтальной прокрутки страницы")
class MobileLayoutTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Path TOOL = Path.of("tools", "mobile-check");
    private static final String LOGIN = "mobile-check@example.com";
    private static final String PASSWORD = "mobile-check-1";
    /** Пользователь до V42: кабинет закрыт окном согласий — проверяется само окно. */
    private static final String RECONSENT_LOGIN = "mobile-reconsent@example.com";
    /** Статья для /instrukcii/mobile-statya и предпросмотра в админке. */
    private Long articleId;
    /** Пользователь проверки — для карточки /admin/users/<id>. */
    private Long userId;

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("все страницы на 320, 375, 414, 768, 1024 и 1280 px")
    void noHorizontalScroll() throws Exception {
        assumeTrue(Files.isDirectory(TOOL.resolve("node_modules")),
                "нет tools/mobile-check/node_modules — выполните npm install в tools/mobile-check");
        assumeTrue(runs("node", "--version"), "node не установлен");

        seed();

        File log = Path.of("target", "mobile-check.log").toFile();
        Process process = null;
        String output = "";
        // Headless Chrome изредка закрывает страницу посреди обхода — это сбой браузера, а не
        // вёрстки: в таком случае обход повторяется один раз. Замечания по вёрстке не повторяются.
        for (int attempt = 1; attempt <= 2; attempt++) {
            ProcessBuilder pb = new ProcessBuilder("node", "check.mjs")
                    .directory(TOOL.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log);
            pb.environment().put("BASE_URL", "http://localhost:" + port);
            pb.environment().put("LOGIN", LOGIN);
            pb.environment().put("PASSWORD", PASSWORD);
            pb.environment().put("RECONSENT_LOGIN", RECONSENT_LOGIN);
            pb.environment().put("RECONSENT_PASSWORD", PASSWORD);
            pb.environment().put("EXTRA_PATHS", String.join(",", List.of(
                    "/f/mcFeed7", "/f/nope000", "/feedback/mc-feedback", "/feedback/nope",
                    "/housekeeper/mc-keeper", "/housekeeper/nope", "/legal/nope",
                    "/instrukcii/mobile-statya")));
            pb.environment().put("EXTRA_ADMIN_PATHS", "/admin/guides/" + articleId + "/preview,/admin/guides/"
                    + articleId + "/edit,/admin/users/" + userId + ",/admin/users/" + userId + "/delete,/admin/users?q=mobile&sort=login");
            process = pb.start();
            assertThat(process.waitFor(10, TimeUnit.MINUTES)).as("проверка уложилась в 10 минут").isTrue();
            output = Files.readString(log.toPath(), StandardCharsets.UTF_8);
            if (!output.contains("TargetCloseError")) break;
        }

        // код 2 — скрипт не нашёл Chrome: это не ошибка вёрстки
        assumeTrue(process.exitValue() != 2, output);
        assertThat(process.exitValue()).as(output).isZero();
    }

    /** Хозяин с объектом, каналами, ручной бронью и страницей бронирования — чтобы страницы были не пустыми. */
    private void seed() {
        Long tenantId = jdbc.queryForObject(
                "INSERT INTO tenants(name, slug) VALUES ('Проверка вёрстки', 'mobile-check') RETURNING id", Long.class);
        userId = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, display_name, role, email)"
                        + " VALUES (?, ?, ?, 'Проверка вёрстки', 'ADMIN', ?) RETURNING id", Long.class,
                tenantId, LOGIN, new BCryptPasswordEncoder().encode(PASSWORD), LOGIN);
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO properties(tenant_id, name, feedback_code, housekeeper_code)"
                        + " VALUES (?, 'Апартаменты на Крепостной с видом на залив', 'mc-feedback', 'mc-keeper')"
                        + " RETURNING id", Long.class, tenantId);
        Long unitTypeId = jdbc.queryForObject(
                "INSERT INTO unit_types(tenant_id, property_id, name, unit_count, base_price)"
                        + " VALUES (?, ?, 'Основной', 1, 4500) RETURNING id", Long.class, tenantId, propertyId);
        Long widgetChannel = jdbc.queryForObject(
                "INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled)"
                        + " VALUES (?, ?, 'WIDGET', 'Прямая бронь', false) RETURNING id",
                Long.class, tenantId, unitTypeId);
        jdbc.update("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled, export_secret)"
                + " VALUES (?, ?, 'ICAL', 'Суточно', false, 'mc-ical-secret')", tenantId, unitTypeId);
        Long widgetId = jdbc.queryForObject(
                "INSERT INTO booking_widgets(tenant_id, channel_id, unit_type_id, secret, slug, title)"
                        + " VALUES (?, ?, ?, 'mc-secret', 'mobile-check', 'Квартира на Садовой у Часовой башни')"
                        + " RETURNING id", Long.class, tenantId, widgetChannel, unitTypeId);
        jdbc.update("INSERT INTO widget_qr_links(tenant_id, widget_id, code, kind) VALUES (?, ?, 'mcFeed7', 'FEEDBACK')",
                tenantId, widgetId);
        // Мастер первой настройки пройден (иначе вход уводил бы в него с шахматки), но его шаги
        // открываются по ?step=: две площадки — одна подключена, другая ждёт ссылку
        jdbc.update("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled, export_secret, color, config_json)"
                + " VALUES (?, ?, 'ICAL', 'Циан', false, 'mc-cian-secret', '#1D4ED8', ?::jsonb)", tenantId, unitTypeId,
                "{\"platform\":\"cian\",\"import_url\":\"https://www.cian.ru/calendar/ical/calendars/1.ics\"}");
        jdbc.update("INSERT INTO channels(tenant_id, unit_type_id, channel_type, name, sync_enabled, export_secret, color, config_json)"
                + " VALUES (?, ?, 'ICAL', 'Яндекс Путешествия', false, 'mc-yandex-secret', '#E5552B', ?::jsonb)",
                tenantId, unitTypeId, "{\"platform\":\"yandex-puteshestviya\"}");
        jdbc.update("INSERT INTO onboarding_state(user_id, tenant_id, branch, wizard_step, wizard_status, data)"
                + " VALUES (?, ?, 'AB', 'done', 'DONE', ?::jsonb)", userId, tenantId,
                "{\"unitTypeId\":" + unitTypeId + ",\"propertyId\":" + propertyId + "}");
        // Сведения об операторе и согласие на показ: на странице бронирования есть контакты и форма
        jdbc.update("UPDATE booking_widgets SET contact_phone = '+7 900 000-00-00' WHERE id = ?", widgetId);
        jdbc.update("INSERT INTO guest_operators(tenant_id, name, contact, inn, address) VALUES (?,"
                + " 'Индивидуальный предприниматель Константинопольская-Щедрина Анастасия Владимировна',"
                + " 'kostantinopolskaya-shchedrina.anastasia@example.com', '470416071381',"
                + " 'Ленинградская область, г. Выборг, ул. Крепостная, д. 12, кв. 34')", tenantId);
        jdbc.update("INSERT INTO public_data_consents(tenant_id, widget_id, fields, document_version) VALUES (?, ?,"
                + " 'phone,operator_name,operator_contact,operator_inn,operator_address', 1)", tenantId, widgetId);
        jdbc.update("INSERT INTO users(tenant_id, username, password_hash, display_name, role, email,"
                        + " legal_reconsent_required) VALUES (?, ?, ?, 'До документов', 'OWNER', ?, TRUE)",
                tenantId, RECONSENT_LOGIN, new BCryptPasswordEncoder().encode(PASSWORD), RECONSENT_LOGIN);
        // Опубликованная статья с оглавлением, врезкой, широкой таблицей и длинным словом
        articleId = jdbc.queryForObject("INSERT INTO guides(kind, slug, title, description, body_md, status, sort_order)"
                + " VALUES ('ARTICLE', 'mobile-statya', 'Длинный заголовок статьи, который переносится на узком экране',"
                + " 'Описание', ?, 'PUBLISHED', 100) RETURNING id", Long.class,
                "## Раздел один\n\nТекст со ссылкой https://example.com/ochen-dlinnyi-adres-bez-probelov-kotoryi-ne-perenositsya\n\n"
                        + "## Раздел два\n\n> **Важно:** врезка.\n\n## Раздел три\n\n"
                        + "| Площадка | Ссылка с площадки | Ссылка для площадки | Как часто | Особенности |\n|---|---|---|---|---|\n"
                        + "| Островок | в кабинете объявления | в настройках календаря | раз в 10–60 минут | длинный текст ячейки |\n\n"
                        + "## Раздел четыре\n\n1. Шаг\n2. Шаг");
        LocalDate from = LocalDate.now().plusDays(2);
        jdbc.update("INSERT INTO calendar_blocks(tenant_id, unit_type_id, from_date, to_date, block_type)"
                + " VALUES (?, ?, ?, ?, 'MAINTENANCE')", tenantId, unitTypeId, from, from.plusDays(3));
    }

    private static boolean runs(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
