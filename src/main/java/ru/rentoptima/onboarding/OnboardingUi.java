package ru.rentoptima.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.rentoptima.security.AuthContext;

import java.util.List;
import java.util.Optional;

/**
 * Что онбординг показывает на страницах кабинета: настройки для {@code onboarding.js},
 * встроенные в страницу. Атрибут {@code onboardingJson} есть в модели, только когда есть что
 * показать, — у остальных пользователей скрипт, стили и шрифт онбординга не загружаются вовсе
 * (см. {@code fragments/layout.html}).
 * <p>
 * Кадры тура описаны данными: что обвести ({@code data-tour} в разметке) и ключ текста.
 * Новый кадр — строка в {@link #FRAMES} и текст в {@code texts_ru.yml}.
 */
@ControllerAdvice
@RequiredArgsConstructor
public class OnboardingUi {

    /** Кадр тура: имя, селектор элемента и нужно ли открыть меню на узком экране. */
    public record Frame(String id, String target, boolean inMenu) {}

    public static final List<Frame> FRAMES = List.of(
            new Frame("grid", "#grid-wrap", false),
            new Frame("platforms", "[data-tour=\"platforms\"]", true),
            new Frame("page", "[data-tour=\"page\"]", true),
            new Frame("help", "[data-tour=\"help\"]", true));

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OnboardingService onboarding;

    @ModelAttribute("onboardingJson")
    public String onboardingJson() {
        Long userId = AuthContext.userId();
        if (userId == null) return null;
        Optional<OnboardingState> state = onboarding.find(userId);
        if (state.isEmpty() || !OnboardingService.tourPending(state.get())) return null;
        return json(OnboardingService.tourFrame(state.get()));
    }

    static String json(int startFrame) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode tour = root.putObject("tour");
        tour.put("start", startFrame);
        // Тур начинается в шахматке: первый кадр — она сама
        tour.put("page", "/calendar/grid");
        tour.put("url", "/onboarding/tour");
        ArrayNode frames = tour.putArray("frames");
        for (Frame f : FRAMES) {
            frames.addObject().put("id", f.id()).put("target", f.target()).put("menu", f.inMenu())
                    .put("text", OnboardingTexts.get("tour." + f.id()));
        }
        ObjectNode labels = root.putObject("labels");
        for (String key : List.of("next", "skip", "done", "counter", "name")) {
            labels.put(key, OnboardingTexts.get("tour.label." + key));
        }
        // «<» экранируется: строка вставляется в <script type="application/json">
        return root.toString().replace("<", "\\u003c");
    }
}
