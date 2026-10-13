package ru.rentoptima.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import jakarta.servlet.http.HttpServletRequest;
import ru.rentoptima.onboarding.OnboardingHints.Hint;
import ru.rentoptima.security.AuthContext;

import java.time.LocalDate;

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
@SuppressWarnings("unchecked")
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
    private final OnboardingChecklist checklist;
    private final OnboardingNotifier notifier;

    /** Страницы кабинета с общим layout: только им нужны чек-лист и подсказки. */
    private static boolean cabinetPage(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "GET".equals(request.getMethod()) && !path.startsWith("/api/") && !path.startsWith("/onboarding")
                && !path.startsWith("/calendar/grid/action") && !path.endsWith("/frame") && !path.endsWith("/print");
    }

    private Optional<OnboardingState> state(HttpServletRequest request) {
        Long userId = AuthContext.userId();
        if (userId == null || !cabinetPage(request)) return Optional.empty();
        // одно обращение к базе на запрос, сколько бы атрибутов модели его ни просили
        Object cached = request.getAttribute("onboardingState");
        if (cached instanceof Optional<?> o) return (Optional<OnboardingState>) o;
        Optional<OnboardingState> found = onboarding.find(userId);
        request.setAttribute("onboardingState", found);
        return found;
    }

    /** Тексты онбординга для шаблонов кабинета (чек-лист, пустые страницы): {@code ${ot['first.title']}}. */
    @ModelAttribute("ot")
    public java.util.Map<String, String> texts() {
        return OnboardingTexts.RU;
    }

    /**
     * Плашки сопровождения: «площадка подключилась», «пришли первые брони». Показываются один раз —
     * при первом открытии кабинета после события.
     */
    @ModelAttribute("onboardingNotices")
    public List<String> notices(HttpServletRequest request) {
        return state(request).filter(s -> !OnboardingService.legacy(s) && !s.wizardActive())
                .map(s -> notifier.takeUnshown(s.getTenantId())).orElse(List.of());
    }

    /** Чек-лист «Первые шаги» — над шахматкой, на главной и числом в шапке. */
    @ModelAttribute("firstSteps")
    public OnboardingChecklist.View firstSteps(HttpServletRequest request) {
        return state(request).map(checklist::view).orElse(null);
    }

    @ModelAttribute("onboardingJson")
    public String onboardingJson(HttpServletRequest request) {
        Optional<OnboardingState> found = state(request);
        if (found.isEmpty()) return null;
        OnboardingState s = found.get();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean tour = OnboardingService.tourPending(s);
        // Пока идёт тур, подсказок нет; тем, кто создан до онбординга, и во время мастера — тоже
        List<Hint> hints = tour || OnboardingService.legacy(s) || s.wizardActive() ? List.of()
                : OnboardingHints.eligible(s.getData(), path, LocalDate.now());
        if (!tour && hints.isEmpty()) return null;
        return json(tour ? OnboardingService.tourFrame(s) : -1, hints);
    }

    static String json(int startFrame) {
        return json(startFrame, List.of());
    }

    /** @param startFrame кадр, с которого начать тур; -1 — тура нет, только подсказки */
    static String json(int startFrame, List<Hint> hints) {
        ObjectNode root = JSON.createObjectNode();
        if (startFrame >= 0) {
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
        }
        if (!hints.isEmpty()) {
            ArrayNode list = root.putArray("hints");
            for (Hint h : hints) {
                list.addObject().put("id", h.id()).put("target", h.target()).put("watch", h.watch())
                        .put("text", OnboardingTexts.get("hint." + h.id()));
            }
            root.put("hintUrl", "/onboarding/hint");
        }
        ObjectNode labels = root.putObject("labels");
        for (String key : List.of("next", "skip", "done", "counter", "name")) {
            labels.put(key, OnboardingTexts.get("tour.label." + key));
        }
        labels.put("ok", OnboardingTexts.get("hint.label.ok"));
        labels.put("hint", OnboardingTexts.get("hint.label.name"));
        // «<» экранируется: строка вставляется в <script type="application/json">
        return root.toString().replace("<", "\\u003c");
    }
}
