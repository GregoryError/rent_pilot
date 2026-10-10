package ru.rentoptima.widget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Бандл виджета {@code static/w.js} лежит в репозитории собранным (docker-сборка про
 * node не знает). Тест следит, чтобы он не разошёлся с исходниками в {@code widget/}.
 */
@DisplayName("Бандл виджета бронирования")
class WidgetBundleTest {

    private static final Path WIDGET = Path.of("widget");
    private static final Path BUNDLE = Path.of("src/main/resources/static/w.js");
    /** Бюджет из требований: JS 35 КБ + CSS 15 КБ, gzip. По отдельности их проверяет build.mjs. */
    private static final int BUDGET_GZIP = 50 * 1024;

    /** Тот же алгоритм, что в widget/build.mjs: путь, перевод строки, содержимое, перевод строки. */
    static String sourceHash() throws Exception {
        List<String> files = new ArrayList<>();
        files.add("build.mjs");
        try (Stream<Path> src = Files.list(WIDGET.resolve("src"))) {
            src.map(p -> "src/" + p.getFileName()).sorted().forEach(files::add);
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (String file : files) {
            sha.update(file.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) '\n');
            sha.update(Files.readAllBytes(WIDGET.resolve(file)));
            sha.update((byte) '\n');
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    @Test
    @DisplayName("w.js собран из текущих исходников — иначе: cd widget && npm run build")
    void bundleIsFresh() throws Exception {
        String firstLine = Files.readAllLines(BUNDLE).get(0);
        Matcher m = Pattern.compile("src:([0-9a-f]{64})").matcher(firstLine);
        assertThat(m.find()).as("в первой строке w.js есть хэш исходников").isTrue();
        assertThat(m.group(1))
                .as("w.js устарел: исходники в widget/ изменились. Пересоберите: cd widget && npm run build")
                .isEqualTo(sourceHash());
    }

    @Test
    @DisplayName("размер бандла в бюджете")
    void bundleFitsBudget() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(Files.readAllBytes(BUNDLE));
        }
        assertThat(out.size()).isLessThanOrEqualTo(BUDGET_GZIP);
    }
}
