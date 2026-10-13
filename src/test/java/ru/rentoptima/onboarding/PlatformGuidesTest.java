package ru.rentoptima.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.onboarding.PlatformGuides.Platform;
import ru.rentoptima.service.ChannelPalette;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Файлы площадок и тексты онбординга")
class PlatformGuidesTest {

    /** Слова, которых хозяин в мастере и подсказках видеть не должен. */
    private static final List<String> FORBIDDEN = List.of("ical", "импорт", "экспорт", "канал", "фид", "тенант");

    @Test
    @DisplayName("шесть площадок в порядке плиток; цвет каждой — из палитры шахматки")
    void platforms() {
        assertThat(PlatformGuides.ALL).extracting(Platform::id).containsExactly(
                "avito", "cian", "sutochno", "ostrovok", "domclick", "yandex-puteshestviya");
        for (Platform p : PlatformGuides.ALL) {
            assertThat(ChannelPalette.contains(p.color())).as("цвет " + p.id()).isTrue();
            assertThat(p.domains()).as("домены " + p.id()).isNotEmpty();
            assertThat(p.letter()).hasSize(1);
            assertThat(p.guidePath()).isEqualTo("/instrukcii/" + p.id());
        }
        // RealtyCalendar в интерфейсе не упоминается — файла для него нет
        assertThat(PlatformGuides.find("rc")).isEmpty();
    }

    @Test
    @DisplayName("Циан и DomClick: домены проверены, частота из замеров, особенности записаны")
    void measuredPlatforms() {
        Platform cian = PlatformGuides.find("cian").orElseThrow();
        assertThat(cian.domainsDraft()).isFalse();
        assertThat(cian.refresh()).isEqualTo("обычно до получаса");
        assertThat(cian.notes()).hasSize(2);
        Platform domclick = PlatformGuides.find("domclick").orElseThrow();
        assertThat(domclick.domainsDraft()).isFalse();
        assertThat(domclick.refresh()).isEqualTo("обычно до часа");
        assertThat(PlatformGuides.find("avito").orElseThrow().refresh()).isEqualTo("10–60 минут");
        assertThat(PlatformGuides.find("avito").orElseThrow().domainsDraft()).isTrue();
    }

    @Test
    @DisplayName("площадка по адресу ссылки: домен и поддомены")
    void byHost() {
        assertThat(PlatformGuides.byHost("www.cian.ru")).map(Platform::id).contains("cian");
        assertThat(PlatformGuides.byHost("24.domclick.ru")).map(Platform::id).contains("domclick");
        assertThat(PlatformGuides.byHost("travel.yandex.ru")).map(Platform::id).contains("yandex-puteshestviya");
        assertThat(PlatformGuides.byHost("yandex.ru")).isEmpty();
        assertThat(PlatformGuides.byHost("notcian.ru")).isEmpty();
        assertThat(PlatformGuides.byHost(null)).isEmpty();
    }

    @Test
    @DisplayName("пока путь по кабинету не проверен, все площадки — черновики")
    void drafts() {
        assertThat(PlatformGuides.ALL).allMatch(Platform::draft);
        assertThat(PlatformGuides.ALL).allMatch(p -> p.fromPlatform().isEmpty() && p.toPlatform().isEmpty());
    }

    @Test
    @DisplayName("в текстах мастера и в файлах площадок нет технических слов")
    void plainLanguage() throws Exception {
        for (var e : OnboardingTexts.RU.entrySet()) {
            for (String word : FORBIDDEN) {
                assertThat(e.getValue().toLowerCase(Locale.ROOT)).as("текст " + e.getKey()).doesNotContain(word);
            }
        }
        for (Platform p : PlatformGuides.ALL) {
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
