package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import ru.rentoptima.guide.GuideEditor;
import ru.rentoptima.guide.GuidePageView;
import ru.rentoptima.guide.GuideRepository;
import ru.rentoptima.seo.SitePageModel;
import ru.rentoptima.seo.SitePages;

import java.security.Principal;
import java.util.List;

/**
 * Публичный сайт: главная, посадочные страницы и инструкции. Тексты — в шаблонах
 * {@code pages/site/*}, заголовки, описания и вопросы — в {@link SitePages}; инструкции и
 * статьи — в базе ({@link GuideRepository}).
 */
@Controller
@RequiredArgsConstructor
public class SiteController {

    static final String DRAFT_NOTICE = "Черновик: страница не опубликована, её видит только администратор.";

    private final SitePageModel pageModel;
    private final GuideRepository guides;
    private final GuidePageView guideView;
    private final GuideEditor editor;

    /** Аноним видит главную; вошедший хозяин сразу попадает в шахматку, как и раньше. */
    @GetMapping("/")
    public String home(Principal principal, Model model, HttpServletRequest request) {
        if (principal != null) return "redirect:/calendar/grid";
        return render(SitePages.HOME, model, request);
    }

    @GetMapping({SitePages.CALENDAR, SitePages.WIDGET, SitePages.WIDGET_TILDA, SitePages.SYNC, SitePages.GUIDES})
    public String landing(Model model, HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (SitePages.GUIDES.equals(path) || SitePages.SYNC.equals(path)) {
            // Ссылки на инструкции (/instrukcii, /sinhronizaciya): все опубликованные, в том числе
            // скрытые из поиска — они открываются
            List<GuideRepository.Guide> published = guides.published();
            model.addAttribute("platformPages", published.stream().filter(GuideRepository.Guide::isPlatform)
                    .map(SitePages::guidePage).toList());
            model.addAttribute("articles", published.stream().filter(g -> !g.isPlatform()).toList());
        }
        return render(path, model, request);
    }

    /**
     * Инструкция по площадке или статья. Черновик — 404 для всех, кроме администратора: ему
     * показывается рабочая копия с плашкой, закрытая от поиска.
     */
    @GetMapping(SitePages.GUIDES + "/{slug:[a-z][a-z0-9-]*}")
    public String guide(@PathVariable String slug, Model model, HttpServletRequest request) {
        GuideRepository.Guide g = guides.bySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (g.published()) return guideView.render(g, null, model, request);
        if (!request.isUserInRole("ADMIN")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return guideView.render(editor.withDraft(g), DRAFT_NOTICE, model, request);
    }

    private String render(String path, Model model, HttpServletRequest request) {
        SitePages.Page page = SitePages.find(path)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return pageModel.fill(page, null, model, request);
    }
}
