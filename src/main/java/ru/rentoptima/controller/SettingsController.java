package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.AutopilotSchedulerService;
import ru.rentoptima.service.SettingsService;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;
    private final AutopilotSchedulerService autopilotScheduler;

    @GetMapping
    public String settings(Model model) {
        Long tenantId = AuthContext.tenantId();
        model.addAttribute("activePage", "settings");
        model.addAttribute("settings", settingsService.getAllForTenant(tenantId));
        model.addAttribute("fields", FIELDS);
        return "pages/settings/index";
    }

    /**
     * Подпись и пояснение к полю настроек.
     *
     * @param label подпись над полем
     * @param hint  для чего поле и на что влияет
     */
    public record Field(String label, String hint) {}

    /**
     * Подписи задаются здесь, а не берутся из system_settings.description: у настроек,
     * созданных при регистрации, описание в базе пустое, и поля оставались без подписей.
     */
    private static final Map<String, Field> FIELDS = fields();

    private static Map<String, Field> fields() {
        Map<String, Field> f = new LinkedHashMap<>();
        f.put("city", new Field("Город для новых квартир",
                "Подставляется подсказкой, когда вы добавляете квартиру. У каждой квартиры свой город — "
                        + "его и учитывает AI-помощник."));
        f.put("timezone", new Field("Часовой пояс",
                "В формате Europe/Moscow. Сейчас все даты и время в системе считаются по Москве."));
        f.put("platform_markup_pct", new Field("Наценка площадок, %",
                "Средняя комиссия площадок. Цена для гостя на площадке = ваша цена + эта наценка; "
                        + "по ней же считается чистый доход с ночи."));
        f.put("cleaning_cost", new Field("Стоимость уборки, ₽",
                "Расход на одну уборку после выезда. Вычитается при расчёте чистого дохода "
                        + "и автоматически попадает в расходы."));
        f.put("weekday_base_price", new Field("Цена в будни для новых квартир, ₽ за ночь",
                "Только подсказка в поле цены, когда вы добавляете квартиру. На расчёты не влияет: "
                        + "рекомендации считаются от цены самой квартиры."));
        f.put("weekend_base_price", new Field("Цена в выходные для новых квартир, ₽ за ночь",
                "То же для ночей с пятницы и с субботы."));
        f.put("min_price_floor", new Field("Минимальная цена, ₽ за ночь",
                "Ниже этой цены рекомендации не опускаются, даже для «окон» между бронями. "
                        + "Пусто — не ниже половины цены квартиры."));
        f.put("max_price_ceiling", new Field("Максимальная цена, ₽ за ночь",
                "Выше этой цены рекомендации не поднимаются, даже в праздники и при высоком спросе. "
                        + "Пусто — не выше 2,5 цены квартиры."));
        f.put("auto_price_delta", new Field("Порог изменения цены, ₽",
                "Только для режима «Осторожный»: рекомендации, которые меняют цену сильнее "
                        + "этого порога, пропускаются."));
        f.put("open_ahead_days", new Field("Горизонт расчёта, дней",
                "На сколько дней вперёд автопилот пересчитывает рекомендации."));
        f.put("max_min_stay", new Field("Предел минимального срока, ночей",
                "Самый длинный минимальный срок проживания, который система может порекомендовать."));
        f.put("autopilot_mode", new Field("Режим автопилота",
                "Автопилот только считает рекомендации и записывает их в журнал — цены на площадках "
                        + "он не меняет. Применять рекомендации вы решаете сами."));
        f.put("autopilot_interval_minutes", new Field("Как часто пересчитывать",
                "Интервал, с которым автопилот обновляет рекомендации, если он включён."));
        f.put("anthropic_api_key", new Field("API-ключ Anthropic (Claude)",
                "Нужен для AI-помощника, AI-корректировки цен и анализа конкурентов. "
                        + "Без ключа эти функции не работают, остальное — работает."));
        return f;
    }

    @PostMapping
    public String updateSettings(@RequestParam Map<String, String> params,
                                  RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        params.entrySet().removeIf(e -> e.getKey().startsWith("_"));
        settingsService.updateSettings(tenantId, params);
        // Reschedule autopilot if interval or mode changed
        autopilotScheduler.onSettingsChanged(tenantId);
        redirect.addFlashAttribute("success", "Настройки сохранены");
        return "redirect:/settings";
    }
}
