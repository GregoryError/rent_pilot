package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import ru.rentoptima.guide.GuideRepository;
import ru.rentoptima.onboarding.PlatformGuides;
import ru.rentoptima.seo.SitePages;
import ru.rentoptima.seo.SitePages.Page;
import ru.rentoptima.seo.SiteSettings;
import ru.rentoptima.seo.StructuredData;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

/**
 * Публичный сайт: главная, посадочные страницы и инструкции. Тексты — в шаблонах
 * {@code pages/site/*}, заголовки, описания и вопросы — в {@link SitePages}.
 */
@Controller
@RequiredArgsConstructor
public class SiteController {

    private final SiteSettings site;
    private final GuideRepository guides;
    private final PlatformGuides platformGuides;

    /** Аноним видит главную; вошедший хозяин сразу попадает в шахматку, как и раньше. */
    @GetMapping("/")
    public String home(Principal principal, Model model, HttpServletRequest request) {
        if (principal != null) return "redirect:/calendar/grid";
        return render(SitePages.HOME, model, request);
    }

    @GetMapping({SitePages.CALENDAR, SitePages.WIDGET, SitePages.WIDGET_TILDA, SitePages.SYNC, SitePages.GUIDES})
    public String landing(Model model, HttpServletRequest request) {
        return render(request.getRequestURI().substring(request.getContextPath().length()), model, request);
    }

    /** Инструкция по площадке: структурные поля и текст — из базы (guides, V45). Черновик — 404. */
    @GetMapping(SitePages.GUIDES + "/{slug:[a-z][a-z0-9-]*}")
    public String guide(@PathVariable String slug, Model model, HttpServletRequest request) {
        GuideRepository.Guide g = guides.bySlug(slug).filter(GuideRepository.Guide::published)
                .filter(GuideRepository.Guide::isPlatform)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        PlatformGuides.Platform p = platformGuides.find(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("guide", g);
        model.addAttribute("platform", p);
        // «обычно до получаса» читается как есть, «10–60 минут» нужен предлог
        model.addAttribute("refresh", Character.isDigit(p.refresh().charAt(0)) ? "раз в " + p.refresh() : p.refresh());
        return render(SitePages.guidePage(g), model, request);
    }

    private String render(String path, Model model, HttpServletRequest request) {
        return render(SitePages.find(path).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)),
                model, request);
    }

    private String render(Page page, Model model, HttpServletRequest request) {
        String path = page.path();
        // Адреса в разметке и canonical — от настоящего домена на prod, от адреса запроса вне его
        String base = site.canonical(request, "");
        boolean home = SitePages.HOME.equals(path);

        List<String> jsonLd = new ArrayList<>();
        if (home) {
            jsonLd.add(StructuredData.organization(base));
            jsonLd.add(StructuredData.website(base));
            jsonLd.add(StructuredData.softwareApplication(base, "/", page.description()));
        } else {
            jsonLd.add(StructuredData.breadcrumbs(base, SitePages.crumbs(page)));
        }
        if (!page.faq().isEmpty()) jsonLd.add(StructuredData.faqPage(page.faq()));

        model.addAttribute("page", page);
        model.addAttribute("canonical", base + (home ? "/" : path));
        model.addAttribute("ogImage", base + "/img/og-default.png");
        model.addAttribute("jsonLd", jsonLd);
        model.addAttribute("crumbs", home ? null : SitePages.crumbs(page));
        // Список /instrukcii: опубликованные инструкции и статьи из базы
        model.addAttribute("guides", guides.published().stream().map(SitePages::guidePage).toList());
        return "pages/site/" + page.template();
    }
}
