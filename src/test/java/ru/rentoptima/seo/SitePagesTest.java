package ru.rentoptima.seo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.seo.SitePages.Page;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Страницы сайта: заголовки, описания, шаблоны")
class SitePagesTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates/pages/site");

    @Test
    @DisplayName("у каждой страницы свой заголовок до 60 символов и своё описание до 170")
    void titlesAndDescriptionsAreUniqueAndFit() {
        Set<String> titles = new HashSet<>(), descriptions = new HashSet<>(), paths = new HashSet<>();
        for (Page p : SitePages.PAGES) {
            assertThat(p.title().length()).as("заголовок " + p.path()).isBetween(20, 60);
            assertThat(p.description().length()).as("описание " + p.path()).isBetween(110, 170);
            assertThat(titles.add(p.title())).as("заголовок повторяется: " + p.title()).isTrue();
            assertThat(descriptions.add(p.description())).as("описание повторяется: " + p.path()).isTrue();
            assertThat(paths.add(p.path())).as("адрес повторяется: " + p.path()).isTrue();
            assertThat(p.path()).as("адрес — латиница без слэша на конце").matches("/|(/[a-z0-9-]+)+");
            assertThat(p.lastmod()).isBeforeOrEqualTo(LocalDate.now());
        }
        // Бренд кириллицей — в заголовке и описании главной
        Page home = SitePages.find(SitePages.HOME).orElseThrow();
        assertThat(home.title()).startsWith("OptiRent (ОптиРент)");
        assertThat(home.description()).contains("ОптиРент");
    }

    @Test
    @DisplayName("у каждой страницы есть шаблон с одним H1, шапкой, подвалом и призывом к регистрации")
    void everyPageHasTemplateWithSingleH1() throws Exception {
        for (Page p : SitePages.PAGES) {
            Path file = TEMPLATES.resolve(p.template() + ".html");
            assertThat(file).as("шаблон " + p.path()).exists();
            String html = Files.readString(file, StandardCharsets.UTF_8);
            assertThat(html.split("<h1[ >]", -1).length - 1).as("H1 в " + file).isEqualTo(1);
            assertThat(html).as(file.toString()).contains("fragments/public :: head", "fragments/public :: header",
                    "fragments/public :: footer", "fragments/public :: cta");
            // «Войти» — только в шапке: в тексте страницы ссылок на вход нет
            assertThat(html).as(file.toString()).doesNotContain("/login");
            // без внешних ресурсов: шрифты, скрипты и картинки — только свои
            assertThat(html).as(file.toString()).doesNotContain("fonts.googleapis", "<script src=\"http", "style=\"background");
        }
    }

    @Test
    @DisplayName("хлебные крошки ведут от главной; вопросы есть на главной и двух посадочных")
    void crumbsAndFaq() {
        Page tilda = SitePages.find(SitePages.WIDGET_TILDA).orElseThrow();
        assertThat(SitePages.crumbs(tilda)).extracting(StructuredData.Crumb::path)
                .containsExactly("/", "/modul-bronirovaniya", "/modul-bronirovaniya/tilda");
        assertThat(SitePages.find(SitePages.HOME).orElseThrow().faq()).hasSizeBetween(5, 6);
        assertThat(SitePages.find(SitePages.WIDGET).orElseThrow().faq()).isNotEmpty();
        assertThat(SitePages.find(SitePages.SYNC).orElseThrow().faq()).isNotEmpty();
    }

    @Test
    @DisplayName("список статических страниц: инструкции и статьи — из базы, не из этого списка")
    void guidesAreNotStatic() {
        assertThat(SitePages.contains("/instrukcii")).isTrue();
        assertThat(SitePages.find("/instrukcii/avito")).isEmpty();
        assertThat(SitePages.indexed()).extracting(Page::path).containsExactly("/", "/kalendar-bronirovaniya",
                "/modul-bronirovaniya", "/modul-bronirovaniya/tilda", "/sinhronizaciya", "/instrukcii");
        assertThat(SeoFilter.mayBeIndexed("/sinhronizaciya")).isTrue();
        assertThat(SeoFilter.mayBeIndexed("/instrukcii/cian")).isFalse();
    }

    @Test
    @DisplayName("IndexNow при старте: только страницы, текст которых правили в последние два дня")
    void indexNowAnnouncesOnlyFreshPages() {
        LocalDate written = SitePages.find(SitePages.HOME).orElseThrow().lastmod();
        assertThat(IndexNowService.recentlyChanged(written)).contains("/", "/sinhronizaciya")
                .doesNotContain("/instrukcii/avito");
        assertThat(IndexNowService.recentlyChanged(written.plusDays(2))).isNotEmpty();
        assertThat(IndexNowService.recentlyChanged(written.plusDays(3))).isEmpty();
    }
}
