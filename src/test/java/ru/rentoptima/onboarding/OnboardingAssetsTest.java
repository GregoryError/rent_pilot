package ru.rentoptima.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Онбординг в кабинете: вес скрипта и стилей, кадры тура")
class OnboardingAssetsTest {

    private static final Path STATIC = Path.of("src/main/resources/static");
    /** Бюджет из требований, gzip. */
    private static final int JS_BUDGET = 15 * 1024, CSS_BUDGET = 5 * 1024;

    private static int gzip(Path file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(Files.readAllBytes(file));
        }
        return out.size();
    }

    @Test
    @DisplayName("скрипт не тяжелее 15 КБ, стили — 5 КБ (gzip); шрифт — два файла, кириллица и латиница")
    void budget() throws Exception {
        assertThat(gzip(STATIC.resolve("js/onboarding.js"))).isLessThanOrEqualTo(JS_BUDGET);
        assertThat(gzip(STATIC.resolve("css/onboarding.css"))).isLessThanOrEqualTo(CSS_BUDGET);
        assertThat(STATIC.resolve("fonts/caveat-cyrillic-600-normal.woff2")).exists();
        assertThat(STATIC.resolve("fonts/caveat-latin-600-normal.woff2")).exists();
    }

    @Test
    @DisplayName("без библиотек и внешних адресов; анимируются только transform, opacity и stroke-dashoffset")
    void noLibrariesAndCheapAnimations() throws Exception {
        String js = Files.readString(STATIC.resolve("js/onboarding.js"), StandardCharsets.UTF_8);
        String css = Files.readString(STATIC.resolve("css/onboarding.css"), StandardCharsets.UTF_8);
        String jsCode = js.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        assertThat(jsCode).doesNotContain("rough", "floating-ui", "popper", "import ", "require(");
        assertThat(jsCode.replace("http://www.w3.org/2000/svg", "")).doesNotContain("http://", "https://");
        assertThat(css).doesNotContain("http://", "https://");
        // рисование линии — переход stroke-dashoffset; остальное — keyframes с opacity и transform
        assertThat(jsCode).contains("stroke-dashoffset").contains("prefers-reduced-motion");
        assertThat(css).contains("prefers-reduced-motion");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("transition\\s*:\\s*([^;}]+)").matcher(css);
        while (m.find()) assertThat(m.group(1)).as("transition в onboarding.css").containsAnyOf("transform", "opacity", "none");
    }

    @Test
    @DisplayName("тур — не больше четырёх кадров, подписи короткие, язык простой")
    void tourFrames() {
        assertThat(OnboardingUi.FRAMES).hasSizeBetween(1, 4);
        for (OnboardingUi.Frame f : OnboardingUi.FRAMES) {
            String text = OnboardingTexts.get("tour." + f.id());
            // одна-две строки бабла шириной 300 px
            assertThat(text.length()).as("кадр " + f.id()).isLessThanOrEqualTo(100);
        }
        String json = OnboardingUi.json(2);
        assertThat(json).contains("\"start\":2", "\"page\":\"/calendar/grid\"", "#grid-wrap",
                "[data-tour=\\\"platforms\\\"]", "Это шахматка — календарь всех ваших броней");
        assertThat(json).doesNotContain("<");
    }
}
