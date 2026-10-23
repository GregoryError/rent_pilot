package ru.rentoptima.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.onboarding.PlatformGuides;
import ru.rentoptima.security.TenantUserDetails;
import ru.rentoptima.seo.IndexNowService;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Публичные страницы инструкций и статей /instrukcii/<slug> в prod-окружении: заголовок,
 * описание, canonical, превью для соцсетей, хлебные крошки, оглавление, призыв; черновик — 404
 * всем, кроме администратора; скрытое из поиска — noindex и не в sitemap.xml; IndexNow при
 * публикации. Нужен docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.uploads.dir=target/test-uploads", "app.environment=prod",
        "app.site.base-url=https://optirent.ru"})
@AutoConfigureMockMvc
@DisplayName("Инструкции и статьи на сайте")
class GuidePublicIntegrationTest {

    private static final String BASE = "https://optirent.ru";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformGuides platformGuides;
    @MockBean IndexNowService indexNow;

    private TenantUserDetails admin;
    private TenantUserDetails owner;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        admin = account("ADMIN", User.Role.ADMIN);
        owner = account("OWNER", User.Role.OWNER);
        clearInvocations(indexNow);
    }

    private TenantUserDetails account(String role, User.Role r) {
        String name = "gp-" + role.toLowerCase() + "-" + tag;
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES (?, ?) RETURNING id",
                Long.class, name, name);
        Long id = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role)"
                + " VALUES (?, ?, 'x', ?) RETURNING id", Long.class, tenantId, name, role);
        Tenant t = new Tenant();
        t.setId(tenantId);
        User u = new User();
        u.setId(id);
        u.setTenant(t);
        u.setUsername(name);
        u.setPasswordHash("x");
        u.setRole(r);
        return new TenantUserDetails(u);
    }

    private long article(String slug) throws Exception {
        mvc.perform(post("/admin/guides").with(user(admin)).with(csrf()).param("title", "Как не получить двойную бронь")
                .param("slug", slug)).andExpect(status().is3xxRedirection());
        return jdbc.queryForObject("SELECT id FROM guides WHERE slug = ?", Long.class, slug);
    }

    private MockHttpServletRequestBuilder publish(long id, String slug, String body) {
        return post("/admin/guides/" + id + "/save").with(user(admin)).with(csrf()).param("action", "publish")
                .param("title", "Как не получить двойную бронь").param("slug", slug)
                .param("description", "Что делать хозяину, чтобы даты на площадках закрывались вовремя и гость не приехал в занятую квартиру.")
                .param("sortOrder", "10").param("body", body);
    }

    private JsonNode upload() throws Exception {
        BufferedImage img = new BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 80, 40));
        g.fillRect(0, 0, 800, 500);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        String json = mvc.perform(multipart("/admin/guides/images")
                        .file(new MockMultipartFile("file", "shot.png", "image/png", out.toByteArray()))
                        .with(user(admin)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new ObjectMapper().readTree(json);
    }

    /** Самый большой JPEG загруженного изображения — так его отдаёт og:image. */
    private String largest(JsonNode image) {
        String key = jdbc.queryForObject("SELECT file_key FROM guide_images WHERE id = ?", String.class,
                image.path("id").asLong()).trim();
        String widths = jdbc.queryForObject("SELECT widths FROM guide_images WHERE id = ?", String.class,
                image.path("id").asLong());
        String[] w = widths.split(",");
        return "/media/guides/" + key + "-" + w[w.length - 1].trim() + ".jpg";
    }

    private MockHttpServletResponse open(String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse();
    }

    private static Document html(MockHttpServletResponse r) throws Exception {
        return Jsoup.parse(r.getContentAsString(StandardCharsets.UTF_8));
    }

    private String sitemap() throws Exception {
        return open("/sitemap.xml").getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("опубликованная статья: H1, описание, canonical, OG, крошки, оглавление, призыв; в sitemap и IndexNow")
    void publishedArticle() throws Exception {
        String slug = "dvoinaya-bron-" + tag;
        long id = article(slug);
        JsonNode image = upload();
        String body = "## Почему так бывает\n\nТекст.\n\n![Календарь в кабинете площадки](" + image.path("src").asText()
                + ")\n\n## Что настроить\n\n> **Важно:** проверьте ссылку.\n\n## Как проверить\n\nТекст.\n\n## Если всё-таки случилось\n\nТекст.";
        mvc.perform(publish(id, slug, body)).andExpect(status().is3xxRedirection());

        MockHttpServletResponse r = open("/instrukcii/" + slug);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("X-Robots-Tag")).isNull();
        Document doc = html(r);
        assertThat(doc.select("h1")).hasSize(1);
        assertThat(doc.select("h1").text()).isEqualTo("Как не получить двойную бронь");
        assertThat(doc.select("meta[name=description]").attr("content")).startsWith("Что делать хозяину");
        assertThat(doc.select("meta[name=robots]")).isEmpty();
        assertThat(doc.select("link[rel=canonical]").attr("href")).isEqualTo(BASE + "/instrukcii/" + slug);
        assertThat(doc.select("meta[property=og:url]").attr("content")).isEqualTo(BASE + "/instrukcii/" + slug);
        // нет обложки — первое изображение текста
        assertThat(doc.select("meta[property=og:image]").attr("content")).isEqualTo(BASE + largest(image));
        String ld = doc.select("script[type=application/ld+json]").html();
        assertThat(ld).contains("\"BreadcrumbList\"", "\"Инструкции\"", BASE + "/instrukcii\"");
        assertThat(doc.select("nav.toc li")).hasSize(4);
        assertThat(doc.select("nav.toc a").first().attr("href")).startsWith("#");
        assertThat(doc.select("article.prose picture img").attr("alt")).isEqualTo("Календарь в кабинете площадки");
        assertThat(doc.select("article.prose blockquote.callout")).hasSize(1);
        assertThat(doc.select(".cta-block h2").text()).isEqualTo("Подключите площадки к OptiRent — бесплатно");
        assertThat(doc.select(".cta-block a").attr("href")).isEqualTo("/register");

        assertThat(sitemap()).contains("<loc>" + BASE + "/instrukcii/" + slug + "</loc>");
        assertThat(html(open("/instrukcii")).select("a[href=/instrukcii/" + slug + "]")).hasSize(1);
        verify(indexNow).submit(List.of("/instrukcii/" + slug));

        // обложка важнее картинки из текста
        JsonNode cover = upload();
        mvc.perform(publish(id, slug, body).param("coverImageId", cover.path("id").asText()))
                .andExpect(status().is3xxRedirection());
        assertThat(html(open("/instrukcii/" + slug)).select("meta[property=og:image]").attr("content"))
                .isEqualTo(BASE + largest(cover));

        // снятая с публикации — 404, поисковикам сообщено
        clearInvocations(indexNow);
        mvc.perform(post("/admin/guides/" + id + "/unpublish").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(open("/instrukcii/" + slug).getStatus()).isEqualTo(404);
        assertThat(sitemap()).doesNotContain("/instrukcii/" + slug);
        verify(indexNow).submit("/instrukcii/" + slug);
    }

    @Test
    @DisplayName("черновик: 404 анониму и хозяину, администратору — рабочая копия с noindex; не в sitemap и списке")
    void draft() throws Exception {
        String slug = "chernovik-" + tag;
        long id = article(slug);
        mvc.perform(post("/admin/guides/" + id + "/autosave").with(user(admin)).with(csrf())
                        .param("title", "Как не получить двойную бронь").param("slug", slug)
                        .param("description", "Описание").param("body", "## Раздел\n\nТекст рабочей копии"))
                .andExpect(status().isNoContent());

        assertThat(open("/instrukcii/" + slug).getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/instrukcii/" + slug).with(user(owner))).andReturn().getResponse().getStatus())
                .isEqualTo(404);

        MockHttpServletResponse r = mvc.perform(get("/instrukcii/" + slug).with(user(admin))).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("X-Robots-Tag")).contains("noindex");
        Document doc = html(r);
        assertThat(doc.select("meta[name=robots]").attr("content")).contains("noindex");
        assertThat(doc.select(".draft-notice").text()).contains("Черновик");
        assertThat(doc.select("article.prose").text()).contains("Текст рабочей копии");

        // «Открыть как на сайте» — то же самое из админки; хозяину и анониму — 404
        Document preview = html(mvc.perform(get("/admin/guides/" + id + "/preview").with(user(admin)))
                .andExpect(status().isOk()).andReturn().getResponse());
        assertThat(preview.select("h1").text()).isEqualTo("Как не получить двойную бронь");
        assertThat(preview.select("article.prose").text()).contains("Текст рабочей копии");
        assertThat(preview.select(".draft-notice").text()).contains("Предпросмотр");
        assertThat(open("/admin/guides/" + id + "/preview").getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/admin/guides/" + id + "/preview").with(user(owner))).andReturn().getResponse()
                .getStatus()).isEqualTo(404);

        assertThat(sitemap()).doesNotContain("/instrukcii/" + slug);
        assertThat(html(open("/instrukcii")).select("a[href=/instrukcii/" + slug + "]")).isEmpty();
        verifyNoInteractions(indexNow);
    }

    @Test
    @DisplayName("скрытая из поиска: открывается, noindex, нет в sitemap, IndexNow не вызывается, но есть в списке")
    void hiddenFromSearch() throws Exception {
        String slug = "skrytaya-" + tag;
        long id = article(slug);
        mvc.perform(publish(id, slug, "Текст").param("hiddenFromSearch", "true")).andExpect(status().is3xxRedirection());

        MockHttpServletResponse r = open("/instrukcii/" + slug);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(r.getHeader("X-Robots-Tag")).contains("noindex");
        assertThat(html(r).select("meta[name=robots]").attr("content")).contains("noindex");
        assertThat(sitemap()).doesNotContain("/instrukcii/" + slug);
        assertThat(html(open("/instrukcii")).select("a[href=/instrukcii/" + slug + "]")).hasSize(1);
        verifyNoInteractions(indexNow);
    }

    @Test
    @DisplayName("площадка: шаги из полей, под ними текст статьи; загруженный скриншот — как есть; чужой адрес не публикуется")
    void platformPage() throws Exception {
        long id = jdbc.queryForObject("SELECT id FROM guides WHERE slug = 'ostrovok'", Long.class);
        String before = jdbc.queryForObject("SELECT platform::text FROM guides WHERE id = ?", String.class, id);
        String beforeBody = jdbc.queryForObject("SELECT body_md FROM guides WHERE id = ?", String.class, id);
        JsonNode shot = upload();
        try {
            MockHttpServletRequestBuilder req = post("/admin/guides/" + id + "/save").with(user(admin)).with(csrf())
                    .param("action", "publish").param("title", "Островок: синхронизация календаря с OptiRent по iCal")
                    .param("slug", "ostrovok").param("hiddenFromSearch", "true").param("sortOrder", "4")
                    .param("description", "Как подключить Островок к календарю OptiRent по iCal: где взять ссылку и куда вставить.")
                    .param("body", "## Частые вопросы\n\nТекст под шагами").param("pName", "Островок")
                    .param("pCabinet", "Островка").param("pLetter", "О").param("pColor", "#0B84D8")
                    .param("pDomains", "ostrovok.ru").param("pRefresh", "10–60 минут").param("pStepsVerified", "true")
                    .param("pFromSteps", "Откройте объявление\nНажмите «Календарь»")
                    .param("pToSteps", "Вставьте ссылку из OptiRent в поле для чужого календаря");
            mvc.perform(req.param("pFromScreenshot", "https://evil.example/x.png")).andExpect(status().is3xxRedirection());
            assertThat(jdbc.queryForObject("SELECT body_md FROM guides WHERE id = ?", String.class, id))
                    .isEqualTo(beforeBody);

            req = post("/admin/guides/" + id + "/save").with(user(admin)).with(csrf())
                    .param("action", "publish").param("title", "Островок: синхронизация календаря с OptiRent по iCal")
                    .param("slug", "ostrovok").param("hiddenFromSearch", "true").param("sortOrder", "4")
                    .param("description", "Как подключить Островок к календарю OptiRent по iCal: где взять ссылку и куда вставить.")
                    .param("body", "## Частые вопросы\n\nТекст под шагами").param("pName", "Островок")
                    .param("pCabinet", "Островка").param("pLetter", "О").param("pColor", "#0B84D8")
                    .param("pDomains", "ostrovok.ru").param("pRefresh", "10–60 минут").param("pStepsVerified", "true")
                    .param("pFromSteps", "Откройте объявление\nНажмите «Календарь»")
                    .param("pToSteps", "Вставьте ссылку из OptiRent в поле для чужого календаря")
                    .param("pFromScreenshot", shot.path("src").asText());
            mvc.perform(req).andExpect(status().is3xxRedirection());

            Document doc = html(open("/instrukcii/ostrovok"));
            assertThat(doc.select("h1").text()).isEqualTo("Островок: синхронизация календаря с OptiRent по iCal");
            assertThat(doc.select("ol.list li").eachText()).contains("Откройте объявление", "Нажмите «Календарь»");
            assertThat(doc.select("figure.shot img").attr("src")).isEqualTo(shot.path("src").asText());
            assertThat(doc.select("article.prose h2").text()).isEqualTo("Частые вопросы");
            // текст статьи — после шагов площадки
            String page = doc.html();
            assertThat(page.indexOf("Особенности площадки")).isLessThan(page.indexOf("Текст под шагами"));
            assertThat(platformGuides.find("ostrovok").orElseThrow().fromPlatform().screenshot())
                    .isEqualTo(shot.path("src").asText());
        } finally {
            jdbc.update("UPDATE guides SET platform = ?::jsonb, body_md = ? WHERE id = ?", before, beforeBody, id);
            jdbc.update("DELETE FROM guide_drafts WHERE guide_id = ?", id);
            platformGuides.reload();
        }
    }
}
