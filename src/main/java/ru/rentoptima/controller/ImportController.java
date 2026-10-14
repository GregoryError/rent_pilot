package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.entity.Property;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.security.Tenancy;
import ru.rentoptima.service.ImportService;

/**
 * «Перенести историю из RealtyCalendar»: загрузка выгрузки броней (.xlsx) и очистка
 * перенесённого. Раздел в настройках («Продвинутые») и в меню «Ещё»; прежний адрес /import —
 * постоянный редирект сюда. Название RealtyCalendar здесь — исключение из правила «не
 * упоминать в интерфейсе»: формат файла — их выгрузка, другого источника такого файла нет.
 */
@Controller
@RequestMapping("/settings/transfer")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;
    private final PropertyRepository propertyRepo;

    @GetMapping
    public String importPage(Model model) {
        Long tenantId = AuthContext.tenantId();
        model.addAttribute("activePage", "transfer");
        model.addAttribute("properties", propertyRepo.findByTenantIdAndActiveTrue(tenantId));
        return "pages/settings/transfer";
    }

    @PostMapping
    public String handleUpload(@RequestParam("file") MultipartFile file,
                                @RequestParam Long propertyId,
                                RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        Tenancy.own(propertyRepo.findById(propertyId), Property::getTenantId);
        var result = importService.importBookingsXls(tenantId, propertyId, file);

        String msg = String.format("Перенесено броней: %d, уже были: %d, ошибок: %d",
                result.created(), result.skipped(), result.errors());
        if (!result.errorMessages().isEmpty()) {
            msg += ". Ошибки: " + String.join("; ", result.errorMessages().subList(0, Math.min(3, result.errorMessages().size())));
        }
        redirect.addFlashAttribute("success", msg);
        return "redirect:/settings/transfer";
    }

    @PostMapping("/clear")
    public String clearData(@RequestParam Long propertyId, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        Tenancy.own(propertyRepo.findById(propertyId), Property::getTenantId);
        var result = importService.clearImportedData(tenantId, propertyId);
        redirect.addFlashAttribute("success",
                String.format("Удалено перенесённых броней: %d, расходов на уборку: %d", result.deletedBookings(), result.deletedExpenses()));
        return "redirect:/settings/transfer";
    }

    /** Прежний адрес раздела «Импорт» — постоянный редирект (закладки, старые ссылки). */
    @Controller
    static class LegacyImportRedirect {
        @GetMapping("/import")
        public org.springframework.web.servlet.view.RedirectView legacy() {
            org.springframework.web.servlet.view.RedirectView v =
                    new org.springframework.web.servlet.view.RedirectView("/settings/transfer", true);
            v.setStatusCode(org.springframework.http.HttpStatus.MOVED_PERMANENTLY);
            return v;
        }
    }
}
