package ru.rentoptima.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.onboarding.OnboardingHints.Hint;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Подсказки по ходу работы: данные и правила показа")
class OnboardingHintsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode shown(String... idAndDate) {
        ObjectNode data = JSON.createObjectNode();
        ObjectNode shown = data.putObject("hints").putObject("shown");
        for (int i = 0; i < idAndDate.length; i += 2) shown.put(idAndDate[i], idAndDate[i + 1]);
        return data;
    }

    @Test
    @DisplayName("пять подсказок описаны данными; у каждой есть короткий текст")
    void described() {
        assertThat(OnboardingHints.ALL).extracting(Hint::id).containsExactly(
                "first-booking", "not-fetched", "designer-devices", "first-conflict", "manual-delete");
        for (Hint h : OnboardingHints.ALL) {
            assertThat(h.target()).as(h.id()).isNotBlank();
            assertThat(OnboardingTexts.get("hint." + h.id()).length()).as("текст " + h.id()).isBetween(20, 100);
        }
        // появится позже, в окне дня: её элемента при загрузке страницы нет
        assertThat(OnboardingHints.ALL).filteredOn(Hint::watch).extracting(Hint::id).containsExactly("manual-delete");
    }

    @Test
    @DisplayName("подсказка привязана к странице; * — один отрезок пути")
    void pages() {
        Hint designer = OnboardingHints.ALL.get(2);
        assertThat(designer.matches("/settings/widgets/17/design")).isTrue();
        assertThat(designer.matches("/settings/widgets/17")).isFalse();
        assertThat(designer.matches("/settings/widgets/17/extra/design")).isFalse();
        assertThat(OnboardingHints.eligible(JSON.createObjectNode(), "/calendar/grid", TODAY)).extracting(Hint::id)
                .containsExactly("first-booking", "manual-delete");
        assertThat(OnboardingHints.eligible(JSON.createObjectNode(), "/pricing", TODAY)).isEmpty();
    }

    @Test
    @DisplayName("показанная не повторяется; не больше двух новых за день; на следующий день лимит новый")
    void limits() {
        assertThat(OnboardingHints.eligible(shown("first-booking", "2026-10-01"), "/calendar/grid", TODAY))
                .extracting(Hint::id).containsExactly("manual-delete");
        // одна сегодня — ещё одну можно
        assertThat(OnboardingHints.eligible(shown("first-booking", "2026-10-14"), "/settings/channels", TODAY))
                .extracting(Hint::id).containsExactly("not-fetched");
        // две сегодня — больше ни одной, на какой бы странице ни был
        ObjectNode two = shown("first-booking", "2026-10-14", "not-fetched", "2026-10-14");
        assertThat(OnboardingHints.eligible(two, "/settings/widgets/1/design", TODAY)).isEmpty();
        assertThat(OnboardingHints.eligible(two, "/calendar/grid", TODAY)).isEmpty();
        // завтра — снова можно
        assertThat(OnboardingHints.eligible(two, "/settings/widgets/1/design", TODAY.plusDays(1)))
                .extracting(Hint::id).containsExactly("designer-devices");
    }

    @Test
    @DisplayName("настройки для страницы: только подсказки — без тура; текст и селектор на месте")
    void json() {
        String json = OnboardingUi.json(-1, List.of(OnboardingHints.ALL.get(0)));
        assertThat(json).contains("\"hints\":[{\"id\":\"first-booking\"", "\"target\":\".grid__cell--colored\"",
                "Это бронь с площадки", "\"hintUrl\":\"/onboarding/hint\"", "\"ok\":\"Понятно\"").doesNotContain("\"tour\"");
    }
}
