package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import ru.rentoptima.legal.LegalDocuments;
import ru.rentoptima.legal.LegalDocuments.Version;
import ru.rentoptima.legal.LegalMarkdown;
import ru.rentoptima.seo.SeoFilter;
import ru.rentoptima.seo.SiteSettings;

import java.util.List;

/**
 * Правовые документы: /legal/&lt;slug&gt; — текущая редакция, /legal/&lt;slug&gt;/v&lt;N&gt; — любая
 * (noindex). Тексты — {@link LegalDocuments}, рендер — {@link LegalMarkdown}.
 */
@Controller
@RequiredArgsConstructor
public class LegalController {

    /** Метка в тексте согласия гостя, вместо которой встаёт блок «Оператор». */
    static final String OPERATOR_MARK = "{{operator}}";

    /** Блок «Кому» общего текста согласия гостя. */
    static final String GENERIC_OPERATOR = "**Оператор** — хост, у которого вы бронируете жильё. Его сведения указаны"
            + " в согласии, ссылка на которое стоит у чекбокса в форме заявки.";


    /** Документы в подвале страницы документа. */
    private static final List<String[]> RELATED = List.of(
            new String[]{"/legal/privacy", "Политика обработки персональных данных"},
            new String[]{"/legal/terms", "Пользовательское соглашение"},
            new String[]{"/legal/cookies", "Политика cookie"});

    private final LegalDocuments documents;
    private final SiteSettings site;

    /** Прежний адрес согласия в анкете отзыва: ссылки на него остались в напечатанных QR. */
    @GetMapping("/legal/feedback-consent")
    public ResponseEntity<Void> feedbackConsent() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header(HttpHeaders.LOCATION, "/legal/" + LegalDocuments.REVIEW).build();
    }

    @GetMapping("/legal/{slug}")
    public String page(@PathVariable String slug, Model model, HttpServletRequest request) {
        Version doc = documents.current(slug).orElseThrow(LegalController::notFound);
        String body = LegalDocuments.GUEST_CONSENT.equals(slug)
                ? doc.body().replace(OPERATOR_MARK, GENERIC_OPERATOR) : doc.body();
        return view(model, request, doc, body, null, false);
    }

    @GetMapping("/legal/{slug}/v{version:\\d+}")
    public String version(@PathVariable String slug, @PathVariable int version, Model model,
                          HttpServletRequest request, HttpServletResponse response) {
        Version doc = documents.version(slug, version).orElseThrow(LegalController::notFound);
        String body = LegalDocuments.GUEST_CONSENT.equals(slug)
                ? doc.body().replace(OPERATOR_MARK, GENERIC_OPERATOR) : doc.body();
        response.setHeader(SeoFilter.HEADER, SeoFilter.NOINDEX);
        return view(model, request, doc, body, null, true);
    }

    private String view(Model model, HttpServletRequest request, Version doc, String body, String subtitle,
                        boolean noindex) {
        LegalMarkdown.Rendered rendered = LegalMarkdown.render(body);
        int current = documents.currentVersion(doc.slug());
        List<Version> history = documents.history(doc.slug());
        model.addAttribute("doc", doc);
        model.addAttribute("subtitle", subtitle);
        model.addAttribute("htmlContent", rendered.html());
        model.addAttribute("toc", rendered.toc());
        model.addAttribute("effective", doc.effectiveText());
        model.addAttribute("archived", doc.version() != current);
        model.addAttribute("currentPath", doc.path());
        model.addAttribute("history", history.size() < 2 ? List.of() : history.stream()
                .map(v -> new HistoryRow(v.versionPath(), "Редакция " + v.version() + " — с "
                        + v.effectiveText(), v.version() == doc.version()))
                .toList());
        model.addAttribute("noindex", noindex);
        model.addAttribute("related", RELATED.stream().filter(r -> !r[0].equals(doc.path())).toList());
        model.addAttribute("canonical", site.canonical(request, doc.path()));
        model.addAttribute("description", doc.title() + " сервиса OptiRent — календаря бронирования"
                + " и синхронизации площадок для посуточной аренды. Редакция " + doc.version()
                + " от " + doc.effectiveText() + ".");
        return "pages/legal/view";
    }

    /** Строка списка редакций; {@code shown} — та, что открыта сейчас. */
    public record HistoryRow(String path, String label, boolean shown) {
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
