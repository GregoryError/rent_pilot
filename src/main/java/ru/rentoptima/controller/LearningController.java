package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.PricingLearningService;

@Controller
@RequestMapping("/learning")
@RequiredArgsConstructor
public class LearningController {

    private final PricingLearningService learningService;

    @GetMapping
    public String page(Model model) {
        Long tenantId = AuthContext.tenantId();
        model.addAttribute("stats", learningService.getStats(tenantId));
        model.addAttribute("insights", learningService.getAllInsights(tenantId));
        model.addAttribute("summary", learningService.getLatestSummary(tenantId));
        return "pages/learning/index";
    }

    @PostMapping("/run")
    public String runNow(RedirectAttributes ra) {
        Long tenantId = AuthContext.tenantId();
        String result = learningService.runAnalysisNow(tenantId);
        ra.addFlashAttribute("message", result);
        return "redirect:/learning";
    }

    @PostMapping("/forget-insights")
    public String forgetInsights(RedirectAttributes ra) {
        Long tenantId = AuthContext.tenantId();
        int n = learningService.forgetInsights(tenantId);
        ra.addFlashAttribute("message", "Удалено выводов: " + n);
        return "redirect:/learning";
    }

    @PostMapping("/clear-raw")
    public String clearRaw(RedirectAttributes ra) {
        Long tenantId = AuthContext.tenantId();
        int n = learningService.clearRawDecisions(tenantId);
        ra.addFlashAttribute("message", "Удалено сырых решений: " + n);
        return "redirect:/learning";
    }

    @PostMapping("/reset")
    public String resetAll(RedirectAttributes ra) {
        Long tenantId = AuthContext.tenantId();
        int raw = learningService.clearRawDecisions(tenantId);
        int ins = learningService.forgetInsights(tenantId);
        ra.addFlashAttribute("message",
                "Полный сброс: удалено " + raw + " решений и " + ins + " выводов");
        return "redirect:/learning";
    }
}
