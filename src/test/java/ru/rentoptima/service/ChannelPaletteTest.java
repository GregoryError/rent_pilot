package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.entity.Channel;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChannelPalette")
class ChannelPaletteTest {

    /** Контраст с белым по WCAG. */
    private static double contrastWithWhite(String hex) {
        double[] rgb = new double[3];
        for (int i = 0; i < 3; i++) {
            double c = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
            rgb[i] = c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
        double luminance = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
        return 1.05 / (luminance + 0.05);
    }

    @Test
    @DisplayName("на любом цвете палитры читается белый текст, цвета не повторяются")
    void whiteTextIsReadable() {
        assertThat(ChannelPalette.COLORS).doesNotHaveDuplicates().hasSizeGreaterThanOrEqualTo(20);
        for (String color : ChannelPalette.COLORS) {
            assertThat(color).matches("#[0-9A-F]{6}");
            assertThat(contrastWithWhite(color)).as(color).isGreaterThanOrEqualTo(3.4);
        }
        assertThat(contrastWithWhite(ChannelPalette.NO_CHANNEL_COLOR)).isGreaterThanOrEqualTo(3.4);
    }

    @Test
    @DisplayName("принимаются только цвета палитры")
    void containsOnlyPaletteColors() {
        assertThat(ChannelPalette.contains("#8635F6")).isTrue();
        assertThat(ChannelPalette.contains("#8635f6")).isTrue();
        assertThat(ChannelPalette.contains("#FFFFFF")).isFalse();
        assertThat(ChannelPalette.contains("red; position: fixed")).isFalse();
        assertThat(ChannelPalette.contains(null)).isFalse();
    }

    @Test
    @DisplayName("без выбранного цвета канал получает стабильный цвет из палитры")
    void defaultColorIsStable() {
        Channel c = new Channel();
        c.setId(7L);
        assertThat(ChannelPalette.colorOf(c)).isIn(ChannelPalette.COLORS).isEqualTo(ChannelPalette.colorOf(c));

        c.setColor("#0094b5");
        assertThat(ChannelPalette.colorOf(c)).isEqualTo("#0094B5");
        c.setColor("#123456");
        assertThat(ChannelPalette.colorOf(c)).isIn(ChannelPalette.COLORS);
    }

    @Test
    @DisplayName("буква на плитке — первая буква названия")
    void letter() {
        assertThat(ChannelPalette.letterOf("суточно — Садовая")).isEqualTo("С");
        assertThat(ChannelPalette.letterOf("  Avito")).isEqualTo("A");
        assertThat(ChannelPalette.letterOf(" ")).isEmpty();
        assertThat(ChannelPalette.letterOf(null)).isEmpty();
    }
}
