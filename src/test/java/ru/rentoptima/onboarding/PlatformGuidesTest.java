package ru.rentoptima.onboarding;

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
import ru.rentoptima.onboarding.PlatformGuides.Platform;
import ru.rentoptima.service.ChannelPalette;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Площадки в базе (V45) — те же, что были в onboarding/platforms/*.yml: порядок, названия,
 * домены, частота, особенности; шаги пустые, как и были. Плюс тексты онбординга. Нужен docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.uploads.dir=target/test-uploads"})
@AutoConfigureMockMvc
@DisplayName("Площадки из базы и тексты онбординга")
class PlatformGuidesTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Слова, которых хозяин в мастере и подсказках видеть не должен. */
    private static final List<String> FORBIDDEN = List.of("ical", "импорт", "экспорт", "канал", "фид", "тенант");

    @Autowired PlatformGuides guides;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    @DisplayName("шесть площадок в порядке плиток, как в yml: названия, падеж кабинета, буквы, цвета из палитры")
    void platforms() {
        assertThat(guides.all()).extracting(Platform::id).containsExactly(
                "avito", "cian", "sutochno", "ostrovok", "domclick", "yandex-puteshestviya");
        assertThat(guides.all()).extracting(Platform::name).containsExactly(
                "Авито", "Циан", "Суточно", "Островок", "DomClick", "Яндекс Путешествия");
        assertThat(guides.all()).extracting(Platform::cabinet).containsExactly(
                "Авито", "Циан", "Суточно", "Островка", "DomClick", "Яндекс Путешествий");
        assertThat(guides.all()).extracting(Platform::letter).containsExactly("А", "Ц", "С", "О", "D", "Я");
        assertThat(guides.all()).extracting(Platform::color).containsExactly(
                "#0E9558", "#1D4ED8", "#C92F9B", "#0B84D8", "#3B9A2F", "#E5552B");
        for (Platform p : guides.all()) {
            assertThat(ChannelPalette.contains(p.color())).as("цвет " + p.id()).isTrue();
            assertThat(p.logo()).isNull();
            assertThat(p.guidePath()).isEqualTo("/instrukcii/" + p.id());
        }
        // RealtyCalendar в интерфейсе не упоминается — записи для него нет
        assertThat(guides.find("rc")).isEmpty();
    }

    @Test
    @DisplayName("домены, частота и особенности перенесены без изменений")
    void measuredPlatforms() {
        Platform cian = guides.find("cian").orElseThrow();
        assertThat(cian.domains()).containsExactly("cian.ru");
        assertThat(cian.domainsDraft()).isFalse();
        assertThat(cian.refresh()).isEqualTo("обычно до получаса");
        assertThat(cian.notes()).hasSize(2).first().asString().startsWith("Если вы закрыли дни в OptiRent вручную");
        Platform domclick = guides.find("domclick").orElseThrow();
        assertThat(domclick.domainsDraft()).isFalse();
        assertThat(domclick.refresh()).isEqualTo("обычно до часа");
        assertThat(domclick.notes()).hasSize(2);
        assertThat(guides.find("sutochno").orElseThrow().notes()).singleElement().asString()
                .contains("поле для чужой ссылки в кабинете скрыто");
        assertThat(guides.find("avito").orElseThrow().refresh()).isEqualTo("10–60 минут");
        assertThat(guides.find("avito").orElseThrow().domainsDraft()).isTrue();
        assertThat(guides.find("yandex-puteshestviya").orElseThrow().domains()).containsExactly("travel.yandex.ru");
    }

    @Test
    @DisplayName("площадка по адресу ссылки: домен и поддомены")
    void byHost() {
        assertThat(guides.byHost("www.cian.ru")).map(Platform::id).contains("cian");
        assertThat(guides.byHost("24.domclick.ru")).map(Platform::id).contains("domclick");
        assertThat(guides.byHost("travel.yandex.ru")).map(Platform::id).contains("yandex-puteshestviya");
        assertThat(guides.byHost("yandex.ru")).isEmpty();
        assertThat(guides.byHost("notcian.ru")).isEmpty();
        assertThat(guides.byHost(null)).isEmpty();
    }

    @Test
    @DisplayName("шаги: проверены у Циан, DomClick, Суточно (списки пока пустые), мастер видит пустые — как раньше")
    void steps() {
        assertThat(guides.all()).filteredOn(Platform::stepsVerified).extracting(Platform::id)
                .containsExactlyInAnyOrder("cian", "domclick", "sutochno");
        assertThat(guides.all()).allMatch(p -> p.fromPlatform().isEmpty() && p.toPlatform().isEmpty());
        // Непроверенные шаги мастер не показывает, даже если они вписаны
        jdbc.update("UPDATE guides SET platform = jsonb_set(platform, '{fromPlatform,steps}', '[\"Откройте объявление\"]')"
                + " WHERE slug IN ('avito', 'cian')");
        try {
            guides.reload();
            assertThat(guides.find("avito").orElseThrow().fromPlatform().isEmpty()).isTrue();
            assertThat(guides.find("cian").orElseThrow().fromPlatform().steps()).containsExactly("Откройте объявление");
        } finally {
            jdbc.update("UPDATE guides SET platform = jsonb_set(platform, '{fromPlatform,steps}', '[]')"
                    + " WHERE slug IN ('avito', 'cian')");
            guides.reload();
        }
    }

    @Test
    @DisplayName("страницы площадок открываются, но скрыты из поиска и не входят в sitemap.xml")
    void hiddenFromSearch() throws Exception {
        assertThat(jdbc.queryForList("SELECT slug FROM guides WHERE status = 'PUBLISHED' AND hidden_from_search",
                String.class)).hasSize(6);
        for (Platform p : guides.all()) {
            String html = mvc.perform(get(p.guidePath())).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(html).as(p.id()).contains("<h1>" + p.name() + ": синхронизация календаря с OptiRent по iCal</h1>");
        }
        assertThat(mvc.perform(get("/instrukcii")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("/instrukcii/avito", "/instrukcii/yandex-puteshestviya");
        mvc.perform(get("/instrukcii/nope")).andReturn();
        assertThat(mvc.perform(get("/instrukcii/nope")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("в текстах мастера и у площадок нет технических слов")
    void plainLanguage() throws Exception {
        for (var e : OnboardingTexts.RU.entrySet()) {
            for (String word : FORBIDDEN) {
                assertThat(e.getValue().toLowerCase(Locale.ROOT)).as("текст " + e.getKey()).doesNotContain(word);
            }
        }
        for (Platform p : guides.all()) {
            String shown = String.join(" ", p.notes()) + " " + String.join(" ", p.fromPlatform().steps()) + " "
                    + String.join(" ", p.toPlatform().steps()) + " " + p.refresh();
            for (String word : FORBIDDEN) {
                assertThat(shown.toLowerCase(Locale.ROOT)).as("площадка " + p.id()).doesNotContain(word);
            }
        }
        // и в самом шаблоне мастера — ни одного своего текста мимо файла
        String template = Files.readString(Path.of("src/main/resources/templates/pages/onboarding/wizard.html"),
                StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
        String visible = template.replaceAll("(?s)<[^>]+>", " ").toLowerCase(Locale.ROOT);
        for (String word : Stream.of("импорт", "экспорт", "канал", "фид", "тенант").toList()) {
            assertThat(visible).doesNotContain(word);
        }
    }

    @Test
    @DisplayName("тексты: ключи шагов на месте, подстановки работают")
    void texts() {
        for (String step : List.of("start", "property", "platforms", "links", "page")) {
            assertThat(OnboardingTexts.get("steps." + step)).isNotBlank();
        }
        assertThat(OnboardingTexts.format("common.step", 2, 4)).isEqualTo("Шаг 2 из 4");
        assertThat(OnboardingTexts.format("link.mismatch", "Циан", "Авито"))
                .isEqualTo("Похоже, это ссылка с площадки «Циан», а карточка — «Авито».");
    }
}
