package ru.rentoptima.legal;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Markdown правовых документов: рендер и санитайзер")
class LegalMarkdownTest {

    @Test
    @DisplayName("таблица Markdown превращается в <table> с шапкой внутри блока с прокруткой")
    void table() {
        String html = LegalMarkdown.render("""
                | Чьи данные | Кто оператор |
                |---|---|
                | Гости | **Хост** |
                """).html();

        assertThat(html).contains("<div class=\"table-wrap\"><table>", "<thead>", "<th>Чьи данные</th>",
                "<td><strong>Хост</strong></td>");
    }

    @Test
    @DisplayName("заголовки получают якоря, оглавление — от четырёх разделов второго уровня")
    void anchorsAndToc() {
        LegalMarkdown.Rendered three = LegalMarkdown.render("## 1. Кто мы\n\n## 2. Роли\n\n## 3. Понятия\n");
        assertThat(three.toc()).isEmpty();
        assertThat(three.html()).contains("<h2 id=\"");

        LegalMarkdown.Rendered four = LegalMarkdown.render("## 1. Кто мы\n\n## 2. Роли\n\n## 3. Понятия\n\n### 3.1. Хосты\n\n## 4. Права\n");
        assertThat(four.toc()).extracting(LegalMarkdown.TocItem::text)
                .containsExactly("1. Кто мы", "2. Роли", "3. Понятия", "4. Права");
        assertThat(four.toc()).allSatisfy(t -> assertThat(four.html()).contains("id=\"" + t.id() + "\""));
        assertThat(four.toc().stream().map(LegalMarkdown.TocItem::id).distinct()).hasSize(4);
    }

    @Test
    @DisplayName("вложенные и нумерованные списки, ссылки на документы и почту, автоссылки")
    void listsAndLinks() {
        String html = LegalMarkdown.render("""
                1. Первый
                   - вложенный
                2. Второй

                Смотрите [Политику](/legal/privacy), пишите на kolesnikoff2085@yandex.ru или https://optirent.ru.
                """).html();

        assertThat(html).contains("<ol>", "<ul>", "<li>вложенный</li>", "<a href=\"/legal/privacy\">Политику</a>",
                "<a href=\"mailto:kolesnikoff2085@yandex.ru\">kolesnikoff2085@yandex.ru</a>",
                "<a href=\"https://optirent.ru\">https://optirent.ru</a>");
    }

    @Test
    @DisplayName("script, style, on* и javascript: не проходят — ни сырым HTML, ни ссылкой Markdown")
    void sanitized() {
        String html = LegalMarkdown.render("""
                <script>alert(1)</script>

                <style>body{display:none}</style>

                <img src=x onerror="alert(1)">

                [нажми](javascript:alert(1)) и [ещё](JaVaScRiPt:alert(2))

                <a href="javascript:alert(3)" onclick="alert(4)">ссылка</a>
                """).html();

        assertSafe(html);
        // Сырой HTML виден как текст, а не исполняется
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(Jsoup.parseBodyFragment(html).text()).contains("нажми и ещё");
    }

    /** В разобранном HTML нет исполняемого: тегов script/style/img, атрибутов on*, адресов javascript:. */
    static void assertSafe(String html) {
        Document doc = Jsoup.parseBodyFragment(html);
        assertThat(doc.select("script, style, img, iframe, object")).isEmpty();
        for (Element e : doc.getAllElements()) {
            for (Attribute a : e.attributes()) {
                assertThat(a.getKey()).doesNotStartWith("on");
                assertThat(a.getValue().toLowerCase().replaceAll("\\s", "")).doesNotContain("javascript:");
            }
        }
    }

    @Test
    @DisplayName("заголовок первого уровня в тексте становится разделом: h1 на странице один — название")
    void noSecondH1() {
        assertThat(LegalMarkdown.render("# Раздел\n\nТекст").html()).doesNotContain("<h1").contains("<h2");
    }

    @Test
    @DisplayName("данные хозяина подставляются как текст: без HTML, ссылок и разметки")
    void literal() {
        String name = "<script>alert(1)</script> [Иван](javascript:alert(1)) **жирный** # заголовок";
        String html = LegalMarkdown.render("Оператор — " + LegalMarkdown.literal(name) + ".").html();

        assertSafe(html);
        assertThat(Jsoup.parseBodyFragment(html).select("a, strong, h2, h3")).isEmpty();
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt; [Иван](javascript:alert(1)) **жирный** # заголовок");
    }
}
