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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.entity.User;
import ru.rentoptima.guide.GuideMarkdown;
import ru.rentoptima.onboarding.PlatformGuides;
import ru.rentoptima.security.TenantUserDetails;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Редактор инструкций и статей: изображения и подписи, санитайзер, рабочая копия и публикация,
 * история, поля площадки для мастера. Нужен docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"app.uploads.dir=target/test-uploads"})
@AutoConfigureMockMvc
@DisplayName("Редактор инструкций и статей")
class GuideEditorIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired GuideMarkdown markdown;
    @Autowired PlatformGuides platformGuides;

    private TenantUserDetails admin;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        Long tenantId = jdbc.queryForObject("INSERT INTO tenants(name, slug) VALUES ('Админ', ?) RETURNING id",
                Long.class, "ge-" + tag);
        Long id = jdbc.queryForObject("INSERT INTO users(tenant_id, username, password_hash, role)"
                + " VALUES (?, ?, 'x', 'ADMIN') RETURNING id", Long.class, tenantId, "ge-" + tag);
        Tenant t = new Tenant();
        t.setId(tenantId);
        User u = new User();
        u.setId(id);
        u.setTenant(t);
        u.setUsername("ge-" + tag);
        u.setPasswordHash("x");
        u.setRole(User.Role.ADMIN);
        admin = new TenantUserDetails(u);
    }

    private long article(String slug) throws Exception {
        mvc.perform(post("/admin/guides").with(user(admin)).with(csrf()).param("title", "Как закрыть даты")
                .param("slug", slug)).andExpect(status().is3xxRedirection());
        return jdbc.queryForObject("SELECT id FROM guides WHERE slug = ?", Long.class, slug);
    }

    private MockHttpServletRequestBuilder save(long id, String action, String body) {
        return post("/admin/guides/" + id + "/save").with(user(admin)).with(csrf()).param("action", action)
                .param("title", "Как закрыть даты").param("slug", "kak-zakryt-" + tag)
                .param("description", "Пошагово: как закрыть даты в календаре и на площадках, чтобы не было двойной брони.")
                .param("sortOrder", "10").param("body", body);
    }

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 120, 200));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private String guideStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM guides WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("изображение без подписи не даёт опубликовать; с подписью — публикуется, в HTML <picture>")
    void imageAltRequired() throws Exception {
        long id = article("kak-zakryt-" + tag);
        String json = mvc.perform(multipart("/admin/guides/images")
                        .file(new MockMultipartFile("file", "shot.png", "image/png", png(800, 500)))
                        .with(user(admin)).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String src = new ObjectMapper().readTree(json).path("src").asText();
        assertThat(src).matches("/media/guides/[0-9a-f]{32}-\\d+\\.jpg");
        mvc.perform(get(src)).andExpect(status().isOk());

        mvc.perform(save(id, "publish", "## Шаг 1\n\n![](" + src + ")")).andExpect(status().is3xxRedirection());
        assertThat(guideStatus(id)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT result FROM admin_audit_log WHERE action = 'GUIDE_PUBLISH'"
                + " AND target_id = ? ORDER BY id DESC LIMIT 1", String.class, id)).isEqualTo("REFUSED");

        mvc.perform(save(id, "publish", "## Шаг 1\n\n![Кнопка «Календарь» в кабинете](" + src + ")"))
                .andExpect(status().is3xxRedirection());
        assertThat(guideStatus(id)).isEqualTo("PUBLISHED");
        String html = markdown.render(jdbc.queryForObject("SELECT body_md FROM guides WHERE id = ?", String.class, id)).html();
        Document doc = Jsoup.parseBodyFragment(html);
        Map<String, Object> img = jdbc.queryForMap("SELECT width, height FROM guide_images ORDER BY id DESC LIMIT 1");
        assertThat(doc.select("picture img[width=" + img.get("width") + "][height=" + img.get("height") + "]")).hasSize(1);
        assertThat(((Number) img.get("width")).intValue() * 5).isEqualTo(((Number) img.get("height")).intValue() * 8);
        assertThat(doc.select("picture img").attr("alt")).isEqualTo("Кнопка «Календарь» в кабинете");
        assertThat(doc.select("picture img").attr("style")).startsWith("background:center/cover url(data:image/jpeg;base64,");
    }

    @Test
    @DisplayName("санитайзер: script, обработчики, javascript: и чужие изображения не проходят; врезка «Важно»")
    void sanitized() throws Exception {
        String body = "<script>alert(1)</script>\n\n[ссылка](javascript:alert(1))\n\n"
                + "![чужое](https://evil.example/x.png)\n\n<img src=x onerror=alert(2)>\n\n> **Важно:** проверьте даты";
        String json = mvc.perform(post("/admin/guides/preview").with(user(admin)).with(csrf()).param("body", body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String html = new ObjectMapper().readTree(json).path("html").asText();
        Document doc = Jsoup.parseBodyFragment(html);
        assertThat(doc.select("script, img, iframe")).isEmpty();
        for (org.jsoup.nodes.Element e : doc.getAllElements()) {
            for (org.jsoup.nodes.Attribute a : e.attributes()) {
                assertThat(a.getKey()).doesNotStartWith("on");
                assertThat(a.getValue().toLowerCase()).doesNotContain("javascript:", "evil.example");
            }
        }
        // Сырой HTML виден как текст, а не исполняется
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(doc.select("blockquote.callout")).hasSize(1);
    }

    @Test
    @DisplayName("автосохранение меняет только рабочую копию; «Сохранить» — версия; «Восстановить» — назад в редактор")
    void draftAndHistory() throws Exception {
        long id = article("kak-zakryt-" + tag);
        mvc.perform(save(id, "publish", "Первая редакция")).andExpect(status().is3xxRedirection());
        assertThat(guideStatus(id)).isEqualTo("PUBLISHED");

        mvc.perform(post("/admin/guides/" + id + "/autosave").with(user(admin)).with(csrf())
                        .param("title", "Как закрыть даты").param("slug", "kak-zakryt-" + tag)
                        .param("description", "x").param("body", "Черновик, ещё не готов"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT body_md FROM guides WHERE id = ?", String.class, id)).isEqualTo("Первая редакция");
        assertThat(jdbc.queryForObject("SELECT body_md FROM guide_drafts WHERE guide_id = ?", String.class, id))
                .isEqualTo("Черновик, ещё не готов");
        assertThat(mvc.perform(get("/admin/guides/" + id + "/edit").with(user(admin))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).contains("Черновик, ещё не готов", "есть неопубликованные правки");

        mvc.perform(save(id, "save", "Вторая редакция")).andExpect(status().is3xxRedirection());
        Map<String, Object> v = jdbc.queryForMap("SELECT max(version) AS v, count(*) AS n FROM guide_versions WHERE guide_id = ?", id);
        assertThat(((Number) v.get("n")).intValue()).isEqualTo(3);   // создание, публикация, сохранение
        assertThat(jdbc.queryForObject("SELECT body_md FROM guides WHERE id = ?", String.class, id)).isEqualTo("Первая редакция");

        mvc.perform(post("/admin/guides/" + id + "/restore/2").with(user(admin)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT body_md FROM guide_drafts WHERE guide_id = ?", String.class, id))
                .isEqualTo("Первая редакция");
        mvc.perform(get("/admin/guides/" + id + "/versions/3").with(user(admin))).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_log WHERE target_type = 'guide' AND target_id = ?",
                String.class, id)).contains("GUIDE_CREATE", "GUIDE_PUBLISH", "GUIDE_SAVE", "GUIDE_RESTORE");
    }

    @Test
    @DisplayName("адрес статьи: занятый и кривой не принимаются")
    void slugRules() throws Exception {
        mvc.perform(post("/admin/guides").with(user(admin)).with(csrf()).param("title", "Циан").param("slug", "cian"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/guides").with(user(admin)).with(csrf()).param("title", "Кривой").param("slug", "Плохой адрес"))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guides WHERE kind = 'ARTICLE' AND title IN ('Циан', 'Кривой')",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("площадка: проверенные шаги доходят до мастера; технические слова не публикуются; адрес не меняется")
    void platformFields() throws Exception {
        long id = jdbc.queryForObject("SELECT id FROM guides WHERE slug = 'ostrovok'", Long.class);
        JsonNode before = new ObjectMapper().readTree(jdbc.queryForObject("SELECT platform::text FROM guides WHERE id = ?",
                String.class, id));
        try {
            MockHttpServletRequestBuilder req = post("/admin/guides/" + id + "/save").with(user(admin)).with(csrf())
                    .param("action", "publish").param("title", "Островок: синхронизация календаря с OptiRent по iCal")
                    .param("slug", "hacked").param("hiddenFromSearch", "true").param("sortOrder", "4")
                    .param("description", "Как подключить Островок к календарю OptiRent по iCal: где взять ссылку и куда вставить.")
                    .param("body", "").param("pName", "Островок").param("pCabinet", "Островка").param("pLetter", "О")
                    .param("pColor", "#0B84D8").param("pDomains", "ostrovok.ru").param("pRefresh", "10–60 минут")
                    .param("pStepsVerified", "true").param("pFromSteps", "Откройте объявление\nНажмите «Календарь»")
                    .param("pToSteps", "Вставьте ссылку из OptiRent в поле для чужого календаря");
            mvc.perform(req.param("pNotes", "Ссылка экспорта обновляется раз в час")).andExpect(status().is3xxRedirection());
            assertThat(platformGuides.find("ostrovok").orElseThrow().fromPlatform().isEmpty()).isTrue();   // «экспорт» — не прошло

            mvc.perform(post("/admin/guides/" + id + "/save").with(user(admin)).with(csrf())
                            .param("action", "publish").param("title", "Островок: синхронизация календаря с OptiRent по iCal")
                            .param("slug", "hacked").param("hiddenFromSearch", "true").param("sortOrder", "4")
                            .param("description", "Как подключить Островок к календарю OptiRent по iCal: где взять ссылку и куда вставить.")
                            .param("body", "").param("pName", "Островок").param("pCabinet", "Островка").param("pLetter", "О")
                            .param("pColor", "#0B84D8").param("pDomains", "ostrovok.ru").param("pRefresh", "10–60 минут")
                            .param("pStepsVerified", "true").param("pFromSteps", "Откройте объявление\nНажмите «Календарь»")
                            .param("pToSteps", "Вставьте ссылку из OptiRent в поле для чужого календаря"))
                    .andExpect(status().is3xxRedirection());
            PlatformGuides.Platform p = platformGuides.find("ostrovok").orElseThrow();
            assertThat(p.fromPlatform().steps()).containsExactly("Откройте объявление", "Нажмите «Календарь»");
            assertThat(p.toPlatform().steps()).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT slug FROM guides WHERE id = ?", String.class, id)).isEqualTo("ostrovok");
        } finally {
            jdbc.update("UPDATE guides SET platform = ?::jsonb WHERE id = ?", before.toString(), id);
            jdbc.update("DELETE FROM guide_drafts WHERE guide_id = ?", id);
            platformGuides.reload();
        }
    }
}
