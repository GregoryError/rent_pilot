package ru.rentoptima.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Печатная карточка: QR-код в SVG")
class QrSvgTest {

    private static final Pattern VIEW_BOX = Pattern.compile("viewBox=\"0 0 (\\d+) (\\d+)\"");
    private static final Pattern RUN = Pattern.compile("M(\\d+) (\\d+)h(\\d+)v1h-(\\d+)z");

    /** Матрица модулей, восстановленная из SVG: то, что реально увидит камера. */
    static boolean[][] modules(String svg) {
        Matcher box = VIEW_BOX.matcher(svg);
        assertThat(box.find()).isTrue();
        int size = Integer.parseInt(box.group(1));
        assertThat(box.group(2)).isEqualTo(box.group(1));
        boolean[][] dark = new boolean[size][size];
        Matcher run = RUN.matcher(svg);
        while (run.find()) {
            int x = Integer.parseInt(run.group(1)), y = Integer.parseInt(run.group(2)), w = Integer.parseInt(run.group(3));
            assertThat(run.group(4)).isEqualTo(run.group(3));
            for (int i = 0; i < w; i++) dark[y][x + i] = true;
        }
        return dark;
    }

    /** Читает QR из SVG тем же ZXing, что читают сканеры: растр 6 точек на модуль. */
    static String decode(String svg) throws Exception {
        boolean[][] dark = modules(svg);
        int size = dark.length, scale = 6, side = size * scale;
        int[] pixels = new int[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                pixels[y * side + x] = dark[y / scale][x / scale] ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(side, side, pixels)));
        return new QRCodeReader().decode(bitmap, hints).getText();
    }

    @Test
    @DisplayName("SVG читается обратно в тот же адрес — короткий, длинный, с кириллицей")
    void roundTrip() throws Exception {
        for (String url : new String[] {
                "https://optirent.ru/q/Ab3dE7x",
                "https://staging.optirent.ru/f/Zk9mPq2",
                "https://optirent.ru/b/kvartira-na-sadovoy-12?promo=SNOVA10&utm_source=qr_card&utm_medium=print",
                "https://optirent.ru/b/loft?promo=ЛЕТО10"}) {
            assertThat(decode(QrSvg.svg(url, "QR"))).isEqualTo(url);
        }
    }

    @Test
    @DisplayName("поле тишины — 4 модуля со всех сторон, уровень коррекции M, цвета — чёрный на белом")
    void quietZoneAndColours() throws Exception {
        String url = "https://optirent.ru/q/Ab3dE7x";
        String svg = QrSvg.svg(url, "QR");
        boolean[][] dark = modules(svg);
        int size = dark.length;
        for (int i = 0; i < size; i++) {
            for (int q = 0; q < QrSvg.QUIET_ZONE; q++) {
                assertThat(dark[q][i] || dark[size - 1 - q][i] || dark[i][q] || dark[i][size - 1 - q])
                        .as("поле тишины, модуль " + i + "/" + q).isFalse();
            }
        }
        // Сразу за полем — угловой поисковый узор: поле не шире четырёх модулей
        assertThat(dark[QrSvg.QUIET_ZONE][QrSvg.QUIET_ZONE]).isTrue();
        assertThat(svg).contains("fill=\"#fff\"", "fill=\"#000\"").doesNotContain("width=\"100", "px");

        BitMatrix matrix = QrSvg.matrix(url);
        com.google.zxing.qrcode.encoder.QRCode code = com.google.zxing.qrcode.encoder.Encoder.encode(
                url, ErrorCorrectionLevel.M, Map.of(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8"));
        assertThat(matrix.getWidth()).isEqualTo(code.getMatrix().getWidth() + 2 * QrSvg.QUIET_ZONE);
    }

    @Test
    @DisplayName("Wi-Fi: формат WIFI:T:WPA;S:…;P:…;; и экранирование ; , : \\ \" в имени сети и пароле")
    void wifiFormat() throws Exception {
        assertThat(QrSvg.wifi("HomeNet", "secret123")).isEqualTo("WIFI:T:WPA;S:HomeNet;P:secret123;;");
        assertThat(QrSvg.wifi("Guest", null)).isEqualTo("WIFI:T:nopass;S:Guest;;");
        assertThat(QrSvg.wifi("Guest", "")).isEqualTo("WIFI:T:nopass;S:Guest;;");

        String ssid = "Кафе; \"Уют\", 2:1";
        String password = "p;a,s:s\\w\"o;rd";
        String expected = "WIFI:T:WPA;S:Кафе\\; \\\"Уют\\\"\\, 2\\:1;P:p\\;a\\,s\\:s\\\\w\\\"o\\;rd;;";
        assertThat(QrSvg.wifi(ssid, password)).isEqualTo(expected);
        // И в таком виде строка доезжает через QR без потерь
        assertThat(decode(QrSvg.svg(QrSvg.wifi(ssid, password), "Wi-Fi"))).isEqualTo(expected);
    }

    @Test
    @DisplayName("подпись в SVG экранируется — текст не становится разметкой")
    void labelEscaped() {
        assertThat(QrSvg.svg("https://optirent.ru", "\"><script>alert(1)</script>"))
                .doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("короткий код: 7 символов, без похожих 0/O, 1/l/I; коды не повторяются")
    void shortCodeAlphabet() {
        assertThat(WidgetCardService.CODE_ALPHABET).doesNotContain("0", "O", "1", "l", "I").hasSize(57);
        assertThat(WidgetCardService.CODE_ALPHABET.chars().distinct().count()).isEqualTo(57);

        var linkRepo = org.mockito.Mockito.mock(ru.rentoptima.repository.WidgetQrLinkRepository.class);
        WidgetCardService service = new WidgetCardService(null, linkRepo, null, null, null);
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = service.newCode();
            assertThat(code).hasSize(WidgetCardService.CODE_LENGTH).matches("[2-9A-HJ-NP-Za-km-z]+");
            codes.add(code);
        }
        assertThat(codes).hasSize(2000);

        // Занятый код не выдаётся повторно: генератор берёт следующий
        org.mockito.Mockito.when(linkRepo.existsByCode(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true, true, false);
        assertThat(service.newCode()).hasSize(WidgetCardService.CODE_LENGTH);
        org.mockito.Mockito.verify(linkRepo, org.mockito.Mockito.atLeast(2003)).existsByCode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("бумага: визитки только на A4; открытка и листок — на A4 или на своём размере")
    void paper() {
        assertThat(WidgetCardService.paperFor("CARD", "A6")).isEqualTo("A4");
        assertThat(WidgetCardService.paperFor("A6", "A6")).isEqualTo("A6");
        assertThat(WidgetCardService.paperFor("A6", "A5")).isEqualTo("A4");
        assertThat(WidgetCardService.paperFor("A5", "A5")).isEqualTo("A5");
        assertThat(WidgetCardService.paperFor("A5", null)).isEqualTo("A4");
    }
}
