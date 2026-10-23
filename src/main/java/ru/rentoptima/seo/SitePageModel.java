package ru.rentoptima.seo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import ru.rentoptima.seo.SitePages.Page;

import java.util.ArrayList;
import java.util.List;

/**
 * Общее для всех публичных страниц сайта: canonical, превью для соцсетей, хлебные крошки и
 * микроразметка. Им пользуются {@code SiteController} и предпросмотр статьи в админке
 * («Открыть как на сайте») — страница в предпросмотре собирается так же, как на сайте.
 */
@Component
public class SitePageModel {

    public static final String DEFAULT_OG_IMAGE = "/img/og-default.png";

    private final SiteSettings site;

    public SitePageModel(SiteSettings site) {
        this.site = site;
    }

    /**
     * Кладёт в модель данные страницы и возвращает имя шаблона.
     *
     * @param ogImage путь изображения для превью на нашем сайте; null — общее
     */
    public String fill(Page page, String ogImage, Model model, HttpServletRequest request) {
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
        model.addAttribute("ogImage", base + (ogImage == null ? DEFAULT_OG_IMAGE : ogImage));
        model.addAttribute("jsonLd", jsonLd);
        model.addAttribute("crumbs", home ? null : SitePages.crumbs(page));
        return "pages/site/" + page.template();
    }
}
