package ru.rentoptima.onboarding;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.rentoptima.onboarding.OnboardingFacts.Facts;

import java.util.ArrayList;
import java.util.List;

/**
 * Чек-лист «Первые шаги». Пункты отмечаются сами — по тому, что у хозяина уже есть
 * ({@link OnboardingFacts}), и по двум отметкам в состоянии: открыл шахматку с бронью и открыл
 * лист печати карточки (сам факт печати браузер не сообщает).
 */
@Service
@RequiredArgsConstructor
public class OnboardingChecklist {

    /** Отметки в {@code onboarding_state.data}. */
    public static final String SEEN_BOOKING = "seenBooking";
    public static final String CARD_PRINTED = "cardPrinted";
    public static final String HIDDEN = "stepsHidden";
    public static final String COLLAPSED = "stepsCollapsed";
    public static final String CELEBRATED = "stepsCelebrated";

    /** @param guideHref ссылка на инструкцию площадки — у напоминания «за сутки не забрала ссылку» */
    public record Item(String id, String title, boolean done, String href, String note, String cssClass,
                       String guideHref) {}

    /**
     * @param celebrate все пункты выполнены и поздравление ещё не показывали: блок выводится
     *                  в последний раз — с поздравлением вместо списка
     */
    public record View(List<Item> items, int done, int total, boolean collapsed, boolean celebrate,
                       String progress, int left) {}

    private final OnboardingFacts facts;
    private final OnboardingService onboarding;

    /** Чек-лист для показа; null — показывать нечего (скрыт, пройден, пользователь создан до онбординга). */
    public View view(OnboardingState s) {
        if (OnboardingService.legacy(s) || s.wizardActive() || flag(s, HIDDEN) || flag(s, CELEBRATED)) return null;
        Facts f = facts.of(s.getTenantId());
        List<Item> items = new ArrayList<>();
        items.add(item("property", f.property(), "/onboarding?step=property", null));
        items.add(item("platform", f.platform(), "/onboarding?step=" + (f.property() ? "platforms" : "property"), null));
        // «Сделать» у пункта, который отмечается сам, ведёт к карточкам площадок: проверить, что ссылка вставлена
        // Прошли сутки, а площадка ни разу не забрала ссылку — вместо «отметится само» напоминание с инструкцией
        OnboardingFacts.Stale stale = f.fetched() ? null : facts.staleChannel(s.getTenantId());
        String guide = stale == null || stale.platform() == null ? null
                : PlatformGuides.find(stale.platform()).map(PlatformGuides.Platform::guidePath).orElse(null);
        items.add(new Item("fetched", OnboardingTexts.get("first.fetched"), f.fetched(), "/onboarding?step=links",
                f.fetched() ? null : stale != null ? OnboardingTexts.format("notice.stale", stale.name())
                        : OnboardingTexts.get("first.fetched.wait"),
                f.fetched() ? "onb-steps__item is-done" : stale != null ? "onb-steps__item is-late" : "onb-steps__item",
                guide));
        items.add(item("booking", f.booking() && flag(s, SEEN_BOOKING), "/calendar/grid", null));
        items.add(item("page", f.page(), f.property() ? "/onboarding?step=page" : "/onboarding?step=property", null));
        items.add(item("card", flag(s, CARD_PRINTED),
                f.page() ? "/settings/widgets/" + f.widgetId() + "/card" : "/settings/widgets", null));
        int done = (int) items.stream().filter(Item::done).count();
        onboarding.countChecklist(s, items.stream().filter(Item::done).map(Item::id).toList(), done == items.size());
        return new View(items, done, items.size(), flag(s, COLLAPSED), done == items.size(),
                OnboardingTexts.format("first.progress", done, items.size()), items.size() - done);
    }

    private static Item item(String id, boolean done, String href, String note) {
        return new Item(id, OnboardingTexts.get("first." + id), done, href, note,
                done ? "onb-steps__item is-done" : "onb-steps__item", null);
    }

    private static boolean flag(OnboardingState s, String key) {
        return s.getData().path(key).asBoolean(false);
    }
}
