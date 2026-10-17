package ru.rentoptima.housing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.rentoptima.onboarding.OnboardingTexts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Словарь типов жилья: каждая форма каждого типа и все тексты интерфейса, в которые подставляется
 * слово, — с каждым типом и с «жильём вообще».
 */
@DisplayName("Словарь жилья: формы по типам, подстановка во все тексты")
class HousingTest {

    @Test
    @DisplayName("формы каждого типа: им., род., вин., мн., род. мн., «ваш…»")
    void formsPerKind() {
        assertForms(HousingKind.APARTMENT.housing, "квартира", "квартиры", "квартиру", "квартиры", "квартир", "ваша квартира");
        assertForms(HousingKind.APARTMENTS.housing, "апартаменты", "апартаментов", "апартаменты", "апартаменты",
                "апартаментов", "ваши апартаменты");
        assertForms(HousingKind.HOUSE.housing, "дом", "дома", "дом", "дома", "домов", "ваш дом");
        assertForms(HousingKind.ROOMS.housing, "гостиница", "гостиницы", "гостиницу", "гостиницы", "гостиниц",
                "ваша гостиница");
        assertForms(HousingKind.OTHER.housing, "жильё", "жилья", "жильё", "объекты", "объектов", "ваше жильё");
        assertForms(Housing.GENERIC, "жильё", "жилья", "жильё", "объекты", "объектов", "ваше жильё");
    }

    private static void assertForms(Housing h, String nom, String gen, String acc, String pl, String plGen, String your) {
        assertThat(h.fill("%nom%|%gen%|%acc%|%pl%|%plGen%|%your%"))
                .isEqualTo(String.join("|", nom, gen, acc, pl, plGen, your));
    }

    @Test
    @DisplayName("фразы по типам: «Добавить …», «Цена …», «Ваш…», с заглавной буквы")
    void phrases() {
        assertThat(HousingKind.APARTMENT.housing.fill("Добавить %acc%")).isEqualTo("Добавить квартиру");
        assertThat(HousingKind.APARTMENTS.housing.fill("Добавить %acc%")).isEqualTo("Добавить апартаменты");
        assertThat(HousingKind.HOUSE.housing.fill("Цена %gen%")).isEqualTo("Цена дома");
        assertThat(HousingKind.ROOMS.housing.fill("%Your%: %Cats%")).isEqualTo("Ваша гостиница: Типы номеров");
        assertThat(HousingKind.APARTMENTS.housing.fill("%Your%")).isEqualTo("Ваши апартаменты");
        assertThat(Housing.GENERIC.fill("Добавить %acc%")).isEqualTo("Добавить жильё");
        assertThat(Housing.GENERIC.fill("нет %plGen% в шахматке")).isEqualTo("нет объектов в шахматке");
        assertThat(HousingKind.APARTMENT.housing.fill("+ %Cat%")).isEqualTo("+ Категория");
        assertThat(HousingKind.ROOMS.housing.fill("+ %Cat%")).isEqualTo("+ Тип номера");
    }

    @Test
    @DisplayName("незнакомая метка — ошибка, а не «%опечатка%» на экране")
    void unknownMark() {
        assertThatThrownBy(() -> Housing.GENERIC.fill("Добавить %accc%")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("тип из базы: пусто и незнакомое — квартира")
    void kindOf() {
        assertThat(HousingKind.of(null)).isEqualTo(HousingKind.APARTMENT);
        assertThat(HousingKind.of("house")).isEqualTo(HousingKind.HOUSE);
        assertThat(HousingKind.of("castle")).isEqualTo(HousingKind.APARTMENT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HousingKind.class)
    @DisplayName("все тексты онбординга заполняются каждым типом без остатков меток")
    void onboardingTexts(HousingKind kind) {
        for (Housing h : List.of(kind.housing, Housing.GENERIC)) {
            OnboardingTexts.forHousing(h).forEach((key, text) -> assertThat(text).as(key).doesNotContainPattern("%[A-Za-z]+%"));
        }
        assertThat(OnboardingTexts.forHousing(kind.housing).get("first.property"))
                .isEqualTo("Добавить " + kind.housing.acc());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HousingKind.class)
    @DisplayName("все шаблоны с housing.fill(…) заполняются каждым типом")
    void templateTexts(HousingKind kind) throws IOException {
        List<String> literals = templateLiterals();
        assertThat(literals).hasSizeGreaterThan(10);
        for (String literal : literals) {
            for (Housing h : List.of(kind.housing, Housing.GENERIC)) {
                assertThat(h.fill(literal)).as(literal).doesNotContainPattern("%[A-Za-z]+%");
            }
        }
    }

    /** Строки из {@code housing.fill('…')}, {@code propHousing.fill('…')}, {@code prop.housing.fill('…')}. */
    private static List<String> templateLiterals() throws IOException {
        Pattern p = Pattern.compile("[Hh]ousing\\.fill\\('([^']*)'\\)");
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".html")).toList()) {
                Matcher m = p.matcher(Files.readString(f));
                while (m.find()) out.add(m.group(1));
            }
        }
        return out;
    }
}
