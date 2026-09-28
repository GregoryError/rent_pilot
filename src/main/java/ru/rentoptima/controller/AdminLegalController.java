package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.entity.LegalPage;
import ru.rentoptima.repository.LegalPageRepository;
import ru.rentoptima.security.AuthContext;

import java.time.LocalDateTime;

@Slf4j
@Controller
@RequestMapping("/admin/legal-pages")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminLegalController {

    private final LegalPageRepository legalRepo;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("activePage", "admin-legal");
        model.addAttribute("pages", legalRepo.findAll());
        return "pages/admin/legal-list";
    }

    @GetMapping("/{slug}/edit")
    public String edit(@PathVariable String slug, Model model) {
        var page = legalRepo.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Not found: " + slug));
        model.addAttribute("activePage", "admin-legal");
        model.addAttribute("page", page);
        return "pages/admin/legal-edit";
    }

    @PostMapping("/{slug}")
    public String save(@PathVariable String slug,
                        @RequestParam String title,
                        @RequestParam String content,
                        RedirectAttributes redirect) {
        var page = legalRepo.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Not found: " + slug));

        page.setTitle(title.trim());
        page.setContent(content);
        page.setUpdatedAt(LocalDateTime.now());
        page.setUpdatedByUserId(AuthContext.userId());
        legalRepo.save(page);

        log.info("Legal page '{}' updated by user {}", slug, AuthContext.userId());
        redirect.addFlashAttribute("success", "Страница сохранена");
        return "redirect:/admin/legal-pages";
    }
}
