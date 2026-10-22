package ru.rentoptima.legal;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown правовых документов → безопасный HTML. Один рендер и для страниц /legal/*, и для
 * предпросмотра в админке.
 * <p>
 * commonmark-java (CommonMark + таблицы GFM, якоря заголовков, автоссылки). Сырой HTML
 * в тексте не пропускается ({@code escapeHtml}), опасные адреса ссылок обнуляются
 * ({@code sanitizeUrls}); после рендера всё ещё раз проходит jsoup {@link Safelist}: только
 * теги документа, без script / style / on* и без javascript:. Тексты правят в админке, а
 * в согласие гостя подставляются данные хозяина — поэтому две линии защиты, а не одна.
 */
public final class LegalMarkdown {

    /** Оглавление — у документов, где разделов второго уровня не меньше этого числа. */
    public static final int TOC_MIN_SECTIONS = 4;

    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(), AutolinkExtension.create(), HeadingAnchorExtension.create());

    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    /** Базовый адрес только для разбора относительных ссылок санитайзером; в HTML он не попадает. */
    private static final String BASE = "https://optirent.ru/";

    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "hr", "h2", "h3", "h4", "strong", "em", "del", "code", "pre", "blockquote",
                    "ul", "ol", "li", "a", "table", "thead", "tbody", "tr", "th", "td")
            .addAttributes("a", "href", "title")
            .addAttributes("ol", "start")
            .addAttributes("th", "align")
            .addAttributes("td", "align")
            .addAttributes("h2", "id")
            .addAttributes("h3", "id")
            .addAttributes("h4", "id")
            .addProtocols("a", "href", "http", "https", "mailto")
            .preserveRelativeLinks(true);

    /** Для статей: те же теги и ещё изображения (адрес проверяет GuideMarkdown — только свои). */
    private static final Safelist SAFELIST_IMAGES = new Safelist(SAFELIST)
            .addTags("img")
            .addAttributes("img", "src", "alt")
            .addProtocols("img", "src", "https")
            // копия Safelist этот флаг не переносит: без него /media/… станет абсолютным адресом
            .preserveRelativeLinks(true);

    /** Разобранный Markdown — для проверок по дереву (подписи изображений в статьях). */
    public static org.commonmark.node.Node parse(String markdown) {
        return PARSER.parse(markdown == null ? "" : markdown);
    }

    private LegalMarkdown() {
    }

    public record TocItem(String id, String text) {
    }

    /** @param html готовый безопасный HTML; {@code toc} пуст, если разделов меньше {@link #TOC_MIN_SECTIONS} */
    public record Rendered(String html, List<TocItem> toc) {
    }

    public static Rendered render(String markdown) {
        return render(markdown, false);
    }

    /** @param images пропускать изображения (инструкции и статьи); в правовых документах их нет */
    public static Rendered render(String markdown, boolean images) {
        String raw = RENDERER.render(PARSER.parse(markdown == null ? "" : markdown));
        // Заголовок первого уровня у документа один — название страницы, его выводит шаблон.
        // # в тексте становится разделом второго уровня, а не вторым h1.
        raw = raw.replace("<h1", "<h2").replace("</h1>", "</h2>");
        String clean = Jsoup.clean(raw, BASE, images ? SAFELIST_IMAGES : SAFELIST);

        Document doc = Jsoup.parseBodyFragment(clean);
        doc.outputSettings().prettyPrint(false);
        // Ссылка с вырезанным адресом (javascript: и т. п.) остаётся просто текстом
        for (Element a : doc.select("a")) {
            if (a.attr("href").isBlank()) a.unwrap();
        }
        // Таблица шире экрана прокручивается внутри своего блока, а не вся страница
        for (Element table : doc.select("table")) {
            table.wrap("<div class=\"table-wrap\"></div>");
        }
        List<TocItem> sections = new ArrayList<>();
        for (Element h : doc.select("h2[id]")) {
            sections.add(new TocItem(h.id(), h.text()));
        }
        return new Rendered(doc.body().html(), sections.size() >= TOC_MIN_SECTIONS ? sections : List.of());
    }

    /**
     * Значение, введённое хозяином (имя, контакт, адрес), для подстановки в Markdown: ровно
     * тот текст, который он ввёл, — без разметки, ссылок и HTML. Сначала экранируется
     * пунктуация Markdown, затем — HTML; переводы строк превращаются в пробелы, чтобы значение
     * не начало новый блок.
     */
    public static String literal(String value) {
        if (value == null) return "";
        String oneLine = value.replaceAll("[\\r\\n\\t]+", " ").strip();
        StringBuilder sb = new StringBuilder(oneLine.length() + 16);
        for (int i = 0; i < oneLine.length(); i++) {
            char c = oneLine.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if ("\\`*_[](){}#+-.!|~:'".indexOf(c) >= 0) sb.append('\\');
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
