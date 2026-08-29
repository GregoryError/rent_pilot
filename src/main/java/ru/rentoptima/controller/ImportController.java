package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.ImportService;

@Controller
@RequestMapping("/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;
    private final PropertyRepository propertyRepo;

    @GetMapping
    public String importPage(Model model) {
        Long tenantId = AuthContext.tenantId();
        model.addAttribute("activePage", "settings");
        model.addAttribute("properties", propertyRepo.findByTenantIdAndActiveTrue(tenantId));
        return "pages/import/index";
    }

    @PostMapping
    public String handleUpload(@RequestParam("file") MultipartFile file,
                                @RequestParam Long propertyId,
                                RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        var result = importService.importBookingsXls(tenantId, propertyId, file);

        String msg = String.format("Импорт: %d создано, %d пропущено, %d ошибок",
                result.created(), result.skipped(), result.errors());
        if (!result.errorMessages().isEmpty()) {
            msg += ". Ошибки: " + String.join("; ", result.errorMessages().subList(0, Math.min(3, result.errorMessages().size())));
        }
        redirect.addFlashAttribute("success", msg);
        return "redirect:/import";
    }
}
