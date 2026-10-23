package ru.rentoptima.guide;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import ru.rentoptima.onboarding.PlatformGuides;
import ru.rentoptima.seo.SitePageModel;
import ru.rentoptima.seo.SitePages;

/**
 * Страница {@code /instrukcii/<slug>} — инструкция по площадке или статья. Один шаблон на оба
 * вида: у площадки сверху шаги из структурных полей (те же, что видит мастер), под ними текст
 * статьи; у статьи — только текст. Тем же методом рисуется предпросмотр рабочей копии в админке.
 */
@Component
public class GuidePageView {

    public static final String TEMPLATE = "instrukciya";

    private final GuideMarkdown markdown;
    private final GuideImages images;
    private final SitePageModel pageModel;

    public GuidePageView(GuideMarkdown markdown, GuideImages images, SitePageModel pageModel) {
        this.markdown = markdown;
        this.images = images;
        this.pageModel = pageModel;
    }

    /**
     * @param g      что показать: опубликованная строка или рабочая копия поверх неё
     * @param notice плашка над заголовком (черновик, предпросмотр); null — нет
     */
    public String render(GuideRepository.Guide g, String notice, Model model, HttpServletRequest request) {
        GuideMarkdown.Rendered body = markdown.render(g.body());
        PlatformGuides.Platform platform = null;
        if (g.isPlatform() && g.platform() != null) {
            try {
                platform = PlatformGuides.parse(g.slug(), g.sortOrder(), g.platform());
            } catch (IllegalStateException e) {
                // в рабочей копии может не быть названия площадки — тогда шагов нет, только текст
            }
        }
        SitePages.Page page = SitePages.guidePage(g);
        // В предпросмотре и у черновика — закрыто от поиска, даже если опубликованная версия открыта
        if (notice != null) page = new SitePages.Page(page.path(), page.template(), page.title(), page.description(),
                page.name(), page.parent(), page.lastmod(), false, page.faq());

        model.addAttribute("guide", g);
        model.addAttribute("platform", platform);
        if (platform != null) {
            // «обычно до получаса» читается как есть, «10–60 минут» нужен предлог
            model.addAttribute("refresh", Character.isDigit(platform.refresh().charAt(0))
                    ? "раз в " + platform.refresh() : platform.refresh());
            model.addAttribute("fromShot", PlatformGuides.imageUrl(platform.fromPlatform().screenshot()));
            model.addAttribute("toShot", PlatformGuides.imageUrl(platform.toPlatform().screenshot()));
        }
        model.addAttribute("bodyHtml", body.html());
        model.addAttribute("toc", body.toc());
        model.addAttribute("notice", notice);
        model.addAttribute("preview", notice != null);
        return pageModel.fill(page, ogImage(g, body), model, request);
    }

    /** Превью для соцсетей: обложка, иначе первое изображение текста, иначе общее. */
    private String ogImage(GuideRepository.Guide g, GuideMarkdown.Rendered body) {
        if (g.coverImageId() != null) {
            var cover = images.byId(g.coverImageId());
            if (cover.isPresent()) return cover.get().largest();
        }
        if (body.firstImage() != null) {
            return GuideImages.keyOf(body.firstImage()).flatMap(images::byKey).map(GuideImages.Image::largest)
                    .orElse(body.firstImage());
        }
        return null;
    }
}
