package ru.rentoptima.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PlaceholderController {

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
}
