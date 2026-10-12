package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
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

    @GetMapping(SitePages.GUIDES + "/{platform:[a-z]+}")
    public String guide(@PathVariable String platform, Model model, HttpServletRequest request) {
        return render(SitePages.GUIDES + "/" + platform, model, request);
    }

    private String render(String path, Model model, HttpServletRequest request) {
        Page page = SitePages.find(path).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
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
        model.addAttribute("guides", SitePages.PAGES.stream()
                .filter(p -> SitePages.GUIDES.equals(p.parent())).toList());
        return "pages/site/" + page.template();
    }
}
