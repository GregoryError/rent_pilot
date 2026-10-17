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

    /**
     * @param href      куда ведёт кнопка; null — кнопки нет
     * @param action    подпись кнопки («Сделать», «Как проверить»)
     * @param guideHref ссылка на инструкцию площадки — у напоминания «за сутки не забрала ссылку»
     */
    public record Item(String id, String title, boolean done, String href, String action, String note,
                       String cssClass, String guideHref) {}

    /**
     * @param celebrate все пункты выполнены и поздравление ещё не показывали: блок выводится
     *                  в последний раз — с поздравлением вместо списка
     */
    public record View(List<Item> items, int done, int total, boolean collapsed, boolean celebrate,
                       String progress, int left) {}

    private final OnboardingFacts facts;
    private final OnboardingService onboarding;
    private final ru.rentoptima.housing.HousingVocabulary housingVocabulary;

    /** Чек-лист для показа; null — показывать нечего (скрыт, пройден, пользователь создан до онбординга). */
    public View view(OnboardingState s) {
        if (OnboardingService.legacy(s) || s.wizardActive() || flag(s, HIDDEN) || flag(s, CELEBRATED)) return null;
        Facts f = facts.of(s.getTenantId());
        ru.rentoptima.housing.Housing housing = housingVocabulary.forTenant(s.getTenantId());
        String doIt = OnboardingTexts.get("first.do");
        List<Item> items = new ArrayList<>();
        items.add(new Item("property", OnboardingTexts.get("first.property", housing), f.property(),
                "/onboarding?step=property", doIt, null, css(f.property(), false), null));
        // Пока жилья нет, остальные пункты приглушены и без кнопки: начинать надо с первого
        String locked = f.property() ? null : OnboardingTexts.get("first.locked", housing);
        items.add(item("platform", f.platform(), "/onboarding?step=platforms", doIt, locked));
        // «Площадка забрала ссылку» отмечается сама: кнопка не «Сделать», а «Как проверить» — в
        // диагностику площадок, когда там есть что показать; иначе — короткое пояснение.
        // Прошли сутки, а площадка ни разу не забрала ссылку — вместо пояснения напоминание с инструкцией
        OnboardingFacts.Stale stale = f.fetched() || locked != null ? null : facts.staleChannel(s.getTenantId());
        String guide = stale == null || stale.platform() == null ? null
                : PlatformGuides.find(stale.platform()).map(PlatformGuides.Platform::guidePath).orElse(null);
        String fetchedNote = f.fetched() ? null : locked != null ? locked
                : stale != null ? OnboardingTexts.format("notice.stale", stale.name())
                : f.channels() ? OnboardingTexts.get("first.fetched.wait") : OnboardingTexts.get("first.fetched.explain");
        items.add(new Item("fetched", OnboardingTexts.get("first.fetched"), f.fetched(),
                locked == null && f.channels() ? "/settings/channels/diagnostics" : null,
                OnboardingTexts.get("first.fetched.how"), fetchedNote,
                f.fetched() ? css(true, false) : stale != null ? "onb-steps__item is-late" : css(false, locked != null),
                guide));
        items.add(item("booking", f.booking() && flag(s, SEEN_BOOKING), "/calendar/grid", doIt, locked));
        items.add(item("page", f.page(), "/onboarding?step=page", doIt, locked));
        items.add(item("card", flag(s, CARD_PRINTED),
                f.page() ? "/settings/widgets/" + f.widgetId() + "/card" : "/settings/widgets", doIt, locked));
        int done = (int) items.stream().filter(Item::done).count();
        onboarding.countChecklist(s, items.stream().filter(Item::done).map(Item::id).toList(), done == items.size());
        return new View(items, done, items.size(), flag(s, COLLAPSED), done == items.size(),
                OnboardingTexts.format("first.progress", done, items.size()), items.size() - done);
    }

    /** Пункт; {@code locked} — текст «сначала добавьте жильё»: пункт приглушён и без кнопки. */
    private static Item item(String id, boolean done, String href, String action, String locked) {
        return new Item(id, OnboardingTexts.get("first." + id), done, locked != null ? null : href, action,
                done ? null : locked, css(done, locked != null && !done), null);
    }

    private static String css(boolean done, boolean locked) {
        return done ? "onb-steps__item is-done" : locked ? "onb-steps__item is-locked" : "onb-steps__item";
    }

    private static boolean flag(OnboardingState s, String key) {
        return s.getData().path(key).asBoolean(false);
    }
}
