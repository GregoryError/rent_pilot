package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.legal.LegalDocuments;
import ru.rentoptima.legal.LegalMarkdown;
import ru.rentoptima.security.AuthContext;

import java.time.LocalDate;
import java.util.Map;

/**
 * Редактор правовых документов. Сохранение — всегда новая редакция (прежние не меняются:
 * на них ссылаются согласия). Предпросмотр — тот же рендер, что и на /legal/*.
 */
@Slf4j
@Controller
@RequestMapping("/admin/legal-pages")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminLegalController {

    private final LegalDocuments documents;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "admin-legal");
        model.addAttribute("pages", documents.currentAll());
        return "pages/admin/legal-list";
    }

    @GetMapping("/{slug}/edit")
    public String edit(@PathVariable String slug, Model model) {
        var page = documents.current(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("activePage", "admin-legal");
        model.addAttribute("page", page);
        model.addAttribute("history", documents.history(slug));
        model.addAttribute("today", LocalDate.now());
        return "pages/admin/legal-edit";
    }

    @PostMapping("/{slug}")
    public String save(@PathVariable String slug,
                       @RequestParam String title,
                       @RequestParam String content,
                       @RequestParam(required = false) LocalDate effectiveDate,
                       RedirectAttributes redirect) {
        var page = documents.current(slug).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String cleanTitle = title == null ? "" : title.strip();
        if (cleanTitle.isEmpty() || content == null || content.isBlank()) {
            redirect.addFlashAttribute("error", "Нужны и заголовок, и текст");
            return "redirect:/admin/legal-pages/" + slug + "/edit";
        }
        if (cleanTitle.equals(page.title()) && content.equals(page.body())
                && (effectiveDate == null || effectiveDate.equals(page.effectiveDate()))) {
            redirect.addFlashAttribute("success", "Изменений нет — новая редакция не создана");
            return "redirect:/admin/legal-pages";
        }
        var created = documents.publish(slug, cleanTitle, effectiveDate == null ? LocalDate.now() : effectiveDate,
                content, AuthContext.userId());

        log.info("Legal page '{}' version {} published by user {}", slug, created.version(), AuthContext.userId());
        redirect.addFlashAttribute("success", "Опубликована редакция " + created.version()
                + ". Прежние доступны по /legal/" + slug + "/v1 … v" + (created.version() - 1));
        return "redirect:/admin/legal-pages";
    }

    /** Предпросмотр несохранённого текста тем же рендером, что и на сайте. */
    @PostMapping(value = "/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> preview(@RequestParam String content) {
        LegalMarkdown.Rendered r = LegalMarkdown.render(content);
        return ResponseEntity.ok(Map.of("html", r.html(), "toc", r.toc()));
    }
}
