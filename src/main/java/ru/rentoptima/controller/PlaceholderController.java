package ru.rentoptima.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PlaceholderController {

    @GetMapping("/calendar")
    public String calendar(Model model) {
        model.addAttribute("activePage", "calendar");
        model.addAttribute("pageTitle", "Календарь");
        model.addAttribute("pageDesc", "Визуализация шахматки с overlay рекомендаций и управление датами.");
        model.addAttribute("planned", new String[]{
                "Календарная сетка с ценами и мин. сроками",
                "Интеграция с RealtyCalendar (чтение/запись)",
                "Рекомендации по ценам прямо на календаре",
                "Gap Management — подсветка «дыр» между бронями"
        });
        return "pages/placeholder";
    }

    @GetMapping("/pricing")
    public String pricing(Model model) {
        model.addAttribute("activePage", "pricing");
        model.addAttribute("pageTitle", "Ценообразование");
        model.addAttribute("pageDesc", "Динамическое ценообразование с учётом внешних факторов.");
        model.addAttribute("planned", new String[]{
                "Pricing Engine с настраиваемыми весами факторов",
                "Мониторинг конкурентов (Avito, Cian)",
                "Календарь событий Выборга",
                "Производственный календарь РФ",
                "Режимы автопилота (OFF / SOFT / FULL)"
        });
        return "pages/placeholder";
    }

    @GetMapping("/feedback")
    public String feedback(Model model) {
        model.addAttribute("activePage", "feedback");
        model.addAttribute("pageTitle", "Отзывы гостей");
        model.addAttribute("pageDesc", "Анализ обратной связи и рекомендации по улучшению квартиры.");
        model.addAttribute("planned", new String[]{
                "QR-код для гостей — лёгкая форма обратной связи",
                "AI-анализ текстовых отзывов (кластеризация, сентимент)",
                "Средние оценки по периодам",
                "ROI-рекомендации по улучшению квартиры"
        });
        return "pages/placeholder";
    }

    @GetMapping("/chat")
    public String chat(Model model) {
        model.addAttribute("activePage", "chat");
        model.addAttribute("pageTitle", "AI Чат");
        model.addAttribute("pageDesc", "Встроенный чат с AI-аналитиком для нестандартных вопросов.");
        model.addAttribute("planned", new String[]{
                "Anthropic API интеграция",
                "Контекст объекта и статистики в каждом запросе",
                "Советы по улучшению квартиры",
                "Объяснение решений системы"
        });
        return "pages/placeholder";
    }
}
