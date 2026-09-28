package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import ru.rentoptima.repository.LegalPageRepository;

@Controller
@RequiredArgsConstructor
public class LegalController {

    private final LegalPageRepository legalRepo;

    @GetMapping("/legal/{slug}")
    public String page(@PathVariable String slug, Model model) {
        var page = legalRepo.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Legal page not found: " + slug));
        model.addAttribute("page", page);
        model.addAttribute("htmlContent", renderMarkdown(page.getContent()));
        return "pages/legal/view";
    }

    /**
     * Мини-конвертер markdown → HTML.
     * Обрабатывает: заголовки, списки, параграфы, курсив, жирный.
     * Не подходит для сложного markdown, но покрывает нужды правовых страниц.
     */
    private String renderMarkdown(String md) {
        if (md == null) return "";

        StringBuilder html = new StringBuilder();
        String[] lines = md.split("\n");
        boolean inList = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                if (inList) { html.append("</ul>\n"); inList = false; }
                continue;
            }

            // Headers
            if (trimmed.startsWith("### ")) {
                if (inList) { html.append("</ul>\n"); inList = false; }
                html.append("<h3>").append(escapeInline(trimmed.substring(4))).append("</h3>\n");
            } else if (trimmed.startsWith("## ")) {
                if (inList) { html.append("</ul>\n"); inList = false; }
                html.append("<h2>").append(escapeInline(trimmed.substring(3))).append("</h2>\n");
            } else if (trimmed.startsWith("# ")) {
                if (inList) { html.append("</ul>\n"); inList = false; }
                html.append("<h1>").append(escapeInline(trimmed.substring(2))).append("</h1>\n");
            }
            // List items
            else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                if (!inList) { html.append("<ul>\n"); inList = true; }
                html.append("<li>").append(escapeInline(trimmed.substring(2))).append("</li>\n");
            }
            // Paragraphs
            else {
                if (inList) { html.append("</ul>\n"); inList = false; }
                html.append("<p>").append(escapeInline(trimmed)).append("</p>\n");
            }
        }
        if (inList) html.append("</ul>\n");
        return html.toString();
    }

    private String escapeInline(String s) {
        // Escape HTML first
        s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        // Bold **text**
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        // Italic *text*
        s = s.replaceAll("\\*([^*]+)\\*", "<em>$1</em>");
        return s;
    }
}
