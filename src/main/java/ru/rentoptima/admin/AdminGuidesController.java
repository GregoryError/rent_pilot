package ru.rentoptima.admin;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.guide.GuideEditor;
import ru.rentoptima.guide.GuideEditor.Form;
import ru.rentoptima.guide.GuideImages;
import ru.rentoptima.guide.GuideMarkdown;
import ru.rentoptima.guide.GuideRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.ChannelPalette;
import ru.rentoptima.service.PhotoProcessor;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Инструкции по площадкам и статьи: список, редактор с предпросмотром и изображениями,
 * автосохранение рабочей копии, публикация, история версий.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminGuidesController {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm",
            Locale.forLanguageTag("ru"));

    private final GuideRepository guides;
    private final GuideEditor editor;
    private final GuideMarkdown markdown;
    private final GuideImages images;
    private final AdminAudit audit;

    /** Строка списка: всё уже текстом. */
    public record Row(long id, String title, String path, String kind, String status, String statusColor,
                      boolean unpublished, int order, String updated) {
    }

    @GetMapping("/admin/guides")
    public String list(Model model) {
        List<Row> rows = guidesAll().stream().map(g -> new Row(g.id(), g.title(), g.path(),
                g.isPlatform() ? "Площадка" : "Статья",
                !g.published() ? "черновик" : g.hiddenFromSearch() ? "опубликовано, скрыто из поиска" : "опубликовано",
                !g.published() ? "var(--text-muted)" : g.hiddenFromSearch() ? "var(--amber)" : "var(--green)",
                editor.hasUnpublished(g), g.sortOrder(),
                g.updatedAt() == null ? "" : g.updatedAt().format(WHEN))).toList();
        model.addAttribute("activePage", "admin-guides");
        model.addAttribute("rows", rows);
        return "pages/admin/guides";
    }

    private List<GuideRepository.Guide> guidesAll() {
        List<GuideRepository.Guide> all = new java.util.ArrayList<>(guides.platforms());
        all.addAll(guides.articles());
        return all;
    }

    @PostMapping("/admin/guides")
    public String create(@RequestParam(required = false) String title, @RequestParam(required = false) String slug,
                         RedirectAttributes redirect) {
        String clean = slug == null ? "" : slug.strip().toLowerCase(Locale.ROOT);
        List<String> errors = editor.createErrors(title, clean);
        if (!errors.isEmpty()) {
            redirect.addFlashAttribute("error", String.join(". ", errors));
            return "redirect:/admin/guides";
        }
        long id = editor.createArticle(title, clean, AuthContext.userId());
        audit.ok(AdminAudit.Action.GUIDE_CREATE, "guide", id, "/instrukcii/" + clean);
        return "redirect:/admin/guides/" + id + "/edit";
    }

    @GetMapping("/admin/guides/{id}/edit")
    public String edit(@PathVariable long id, Model model) {
        GuideRepository.Guide g = guide(id);
        Form f = editor.draft(g);
        model.addAttribute("activePage", "admin-guides");
        model.addAttribute("guide", g);
        model.addAttribute("form", f);
        model.addAttribute("platform", platformView(f.platform()));
        model.addAttribute("palette", ChannelPalette.COLORS);
        model.addAttribute("cover", f.coverImageId() == null ? null
                : images.byId(f.coverImageId()).map(GuideImages.Image::src).orElse(null));
        model.addAttribute("unpublished", editor.hasUnpublished(g));
        model.addAttribute("versions", editor.versions(id).stream().map(v -> Map.of(
                "version", v.version(),
                "label", "Версия " + v.version() + " — " + switch (v.kind()) {
                    case "PUBLISH" -> "опубликована";
                    case "RESTORE" -> "восстановлена";
                    default -> "сохранена";
                } + ", " + v.at().format(WHEN) + (v.author() == null ? "" : ", " + v.author()))).toList());
        return "pages/admin/guide-edit";
    }

    /** «Сохранить» и «Опубликовать» — одна форма, разные кнопки. */
    @PostMapping("/admin/guides/{id}/save")
    public String save(@PathVariable long id, @RequestParam Map<String, String> params,
                       @RequestParam(defaultValue = "save") String action, RedirectAttributes redirect) {
        GuideRepository.Guide g = guide(id);
        Form f = form(g, params);
        boolean publish = "publish".equals(action);
        List<String> errors = publish ? editor.publish(g, f, AuthContext.userId()) : editor.save(g, f, AuthContext.userId());
        if (!errors.isEmpty()) {
            // Текст не теряется: рабочая копия сохранена, ошибки — над формой
            editor.autosave(g, f, AuthContext.userId());
            if (publish) {
                audit.record(AdminAudit.Action.GUIDE_PUBLISH, "guide", id, AdminAudit.Result.REFUSED,
                        g.path() + ": " + errors.size() + " замеч.");
            }
            redirect.addFlashAttribute("errors", errors);
            return "redirect:/admin/guides/" + id + "/edit";
        }
        audit.ok(publish ? AdminAudit.Action.GUIDE_PUBLISH : AdminAudit.Action.GUIDE_SAVE, "guide", id,
                GuideRepository.PATH_PREFIX + (g.isPlatform() ? g.slug() : f.slug()));
        redirect.addFlashAttribute("success", publish ? "Опубликовано" : "Сохранено");
        return "redirect:/admin/guides/" + id + "/edit";
    }

    /** Автосохранение раз в 30 секунд и при уходе со страницы: только рабочая копия. */
    @PostMapping("/admin/guides/{id}/autosave")
    public ResponseEntity<Void> autosave(@PathVariable long id, @RequestParam Map<String, String> params) {
        GuideRepository.Guide g = guide(id);
        editor.autosave(g, form(g, params), AuthContext.userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/guides/{id}/unpublish")
    public String unpublish(@PathVariable long id, RedirectAttributes redirect) {
        GuideRepository.Guide g = guide(id);
        editor.unpublish(g);
        audit.ok(AdminAudit.Action.GUIDE_UNPUBLISH, "guide", id, g.path());
        redirect.addFlashAttribute("success", "Снято с публикации: страница теперь 404 для всех, кроме администратора");
        return "redirect:/admin/guides/" + id + "/edit";
    }

    @PostMapping("/admin/guides/{id}/restore/{version}")
    public String restore(@PathVariable long id, @PathVariable int version, RedirectAttributes redirect) {
        GuideRepository.Guide g = guide(id);
        if (!editor.restore(g, version, AuthContext.userId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        audit.ok(AdminAudit.Action.GUIDE_RESTORE, "guide", id, g.path() + ", версия " + version);
        redirect.addFlashAttribute("success", "Версия " + version + " загружена в редактор. На сайте она появится после «Опубликовать»");
        return "redirect:/admin/guides/" + id + "/edit";
    }

    /** Прошлая версия — только просмотр, тем же рендером. */
    @GetMapping("/admin/guides/{id}/versions/{version}")
    public String version(@PathVariable long id, @PathVariable int version, Model model) {
        GuideRepository.Guide g = guide(id);
        GuideEditor.Version v = editor.version(id, version).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("activePage", "admin-guides");
        model.addAttribute("guide", g);
        model.addAttribute("v", v);
        model.addAttribute("when", v.at().format(WHEN));
        model.addAttribute("html", markdown.render(v.form().body()).html());
        return "pages/admin/guide-version";
    }

    /** Предпросмотр тем же рендером, что и на сайте. */
    @PostMapping(value = "/admin/guides/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> preview(@RequestParam(required = false) String body) {
        GuideMarkdown.Rendered r = markdown.render(body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("html", r.html());
        out.put("toc", r.toc());
        out.put("noAlt", GuideMarkdown.imagesWithoutAlt(body));
        return out;
    }

    /** Загрузка изображения: файл, вставка из буфера, перетаскивание — всё сюда. */
    @PostMapping(value = "/admin/guides/images", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            GuideImages.Image i = images.add(file.getBytes());
            audit.ok(AdminAudit.Action.GUIDE_IMAGE, "guide_image", i.id(), i.width() + "×" + i.height());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", i.id());
            out.put("src", i.src());
            out.put("width", i.width());
            out.put("height", i.height());
            return ResponseEntity.ok(out);
        } catch (PhotoProcessor.PhotoException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Файл не прочитался"));
        }
    }

    // --- Форма

    private GuideRepository.Guide guide(long id) {
        return guides.byId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static Form form(GuideRepository.Guide g, Map<String, String> p) {
        JsonNode platform = g.isPlatform() ? GuideEditor.platformFrom(p.get("pName"), p.get("pCabinet"),
                p.get("pLetter"), p.get("pColor"), p.get("pLogo"), p.get("pDomains"), "on".equals(p.get("pDomainsDraft"))
                        || "true".equals(p.get("pDomainsDraft")),
                "on".equals(p.get("pStepsVerified")) || "true".equals(p.get("pStepsVerified")),
                p.get("pFromSteps"), p.get("pFromScreenshot"), p.get("pToSteps"), p.get("pToScreenshot"),
                p.get("pRefresh"), p.get("pNotes")) : null;
        Long cover = null;
        try {
            if (p.get("coverImageId") != null && !p.get("coverImageId").isBlank()) cover = Long.parseLong(p.get("coverImageId"));
        } catch (NumberFormatException ignored) {
            // нет обложки
        }
        int order = 100;
        try {
            if (p.get("sortOrder") != null) order = Math.max(0, Math.min(10_000, Integer.parseInt(p.get("sortOrder").strip())));
        } catch (NumberFormatException ignored) {
            // по умолчанию
        }
        String slug = p.get("slug") == null ? g.slug() : p.get("slug").strip().toLowerCase(Locale.ROOT);
        return new Form(p.getOrDefault("title", ""), slug, p.getOrDefault("description", ""), p.getOrDefault("body", ""),
                cover, "on".equals(p.get("hiddenFromSearch")) || "true".equals(p.get("hiddenFromSearch")), order, platform);
    }

    /** Поля площадки для шаблона: строки списков — по одной в строке. */
    private static Map<String, Object> platformView(JsonNode p) {
        if (p == null) return null;
        Map<String, Object> v = new LinkedHashMap<>();
        for (String key : List.of("name", "cabinet", "letter", "color", "logo", "refresh")) {
            v.put(key, p.path(key).isNull() || p.path(key).isMissingNode() ? "" : p.path(key).asText());
        }
        v.put("domains", GuideEditor.joinLines(p.path("domains")));
        v.put("domainsDraft", p.path("domainsDraft").asBoolean(false));
        v.put("stepsVerified", p.path("stepsVerified").asBoolean(false));
        v.put("fromSteps", GuideEditor.joinLines(p.path("fromPlatform").path("steps")));
        v.put("fromScreenshot", p.path("fromPlatform").path("screenshot").isNull() ? "" : p.path("fromPlatform").path("screenshot").asText(""));
        v.put("toSteps", GuideEditor.joinLines(p.path("toPlatform").path("steps")));
        v.put("toScreenshot", p.path("toPlatform").path("screenshot").isNull() ? "" : p.path("toPlatform").path("screenshot").asText(""));
        v.put("notes", GuideEditor.joinLines(p.path("notes")));
        return v;
    }
}
