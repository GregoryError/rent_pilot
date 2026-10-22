package ru.rentoptima.guide;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import ru.rentoptima.legal.LegalMarkdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Markdown инструкций и статей → HTML. Основа — {@link LegalMarkdown} (тот же разбор и тот же
 * санитайзер), сверху:
 * <ul>
 *   <li>изображения только свои ({@code /media/guides/…}); чужие адреса вырезаются. Своё
 *       превращается в {@code <picture>}: WebP и JPEG всех ширин, размеры, размытая заглушка;</li>
 *   <li>цитата, которая начинается с «**Важно:**», — врезка {@code .callout};</li>
 *   <li>оглавление по H2 — от {@link LegalMarkdown#TOC_MIN_SECTIONS} разделов.</li>
 * </ul>
 * Атрибуты, которые добавляет этот класс (class, srcset, style заглушки), пишутся уже после
 * санитайзера и только из наших данных — из текста статьи в них ничего не попадает.
 */
@Service
public class GuideMarkdown {

    /** «Важно: …» в начале цитаты. */
    static final String CALLOUT_MARK = "Важно";

    public record Rendered(String html, List<LegalMarkdown.TocItem> toc, String firstImage) {
    }

    private final GuideImages images;

    public GuideMarkdown(GuideImages images) {
        this.images = images;
    }

    public Rendered render(String markdown) {
        LegalMarkdown.Rendered base = LegalMarkdown.render(markdown, true);
        Document doc = Jsoup.parseBodyFragment(base.html());
        doc.outputSettings().prettyPrint(false);
        String first = null;
        for (Element img : doc.select("img")) {
            Optional<GuideImages.Image> image = GuideImages.keyOf(img.attr("src")).flatMap(images::byKey);
            if (image.isEmpty()) {
                img.remove();
                continue;
            }
            GuideImages.Image i = image.get();
            if (first == null) first = i.src();
            img.replaceWith(picture(i, img.attr("alt")));
        }
        for (Element quote : doc.select("blockquote")) {
            Element strong = quote.selectFirst("p > strong:first-child");
            if (strong != null && strong.text().startsWith(CALLOUT_MARK)) quote.addClass("callout");
        }
        return new Rendered(doc.body().html(), base.toc(), first);
    }

    private static Element picture(GuideImages.Image i, String alt) {
        Element picture = new Element("picture");
        if (i.webp()) {
            picture.appendElement("source").attr("type", "image/webp").attr("srcset", i.srcset("webp"))
                    .attr("sizes", "(max-width: 760px) 100vw, 760px");
        }
        Element img = picture.appendElement("img").attr("src", i.src()).attr("srcset", i.srcset("jpg"))
                .attr("sizes", "(max-width: 760px) 100vw, 760px").attr("alt", alt)
                .attr("width", String.valueOf(i.width())).attr("height", String.valueOf(i.height()))
                .attr("loading", "lazy").attr("decoding", "async");
        // Заглушка — data:image/jpeg;base64 от PhotoProcessor, без кавычек и скобок
        if (i.lqip() != null && i.lqip().matches("data:image/(jpeg|webp);base64,[A-Za-z0-9+/=]+")) {
            img.attr("style", "background:center/cover url(" + i.lqip() + ")");
        }
        return picture;
    }

    /** Изображения без подписи (alt): с ними статья не публикуется. Возвращает их адреса. */
    public static List<String> imagesWithoutAlt(String markdown) {
        List<String> missing = new ArrayList<>();
        Node root = LegalMarkdown.parse(markdown);
        root.accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                StringBuilder alt = new StringBuilder();
                for (Node n = image.getFirstChild(); n != null; n = n.getNext()) {
                    if (n instanceof Text t) alt.append(t.getLiteral());
                }
                if (alt.toString().isBlank()) missing.add(image.getDestination());
                visitChildren(image);
            }
        });
        return missing;
    }
}
