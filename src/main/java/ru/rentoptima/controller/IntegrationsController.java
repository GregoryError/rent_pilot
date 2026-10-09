package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.rentoptima.entity.AlertEvent;
import ru.rentoptima.repository.AlertEventRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.AlertSchedulerService;
import ru.rentoptima.service.SettingsService;
import ru.rentoptima.service.TelegramService;

import jakarta.servlet.http.HttpServletRequest;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Slf4j
@Controller
@RequestMapping("/settings/integrations")
@RequiredArgsConstructor
public class IntegrationsController {

    private final SettingsService settings;
    private final TelegramService telegram;
    private final AlertSchedulerService alertScheduler;
    private final AlertEventRepository alertRepo;

    private static final DateTimeFormatter ALERT_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    @GetMapping
    public String page(Model model) {
        Long tenantId = AuthContext.tenantId();

        model.addAttribute("activePage", "settings");

        String chatId = telegram.chatId(tenantId);
        model.addAttribute("tgHasToken", telegram.hasToken(tenantId));
        model.addAttribute("tgChatId", chatId != null ? chatId : "");
        model.addAttribute("tgConfigured", telegram.isConfigured(tenantId));
        model.addAttribute("alerts", alertRepo.findTop20ByTenantIdAndAlertTypeNotOrderByFirstSeenAtDesc(
                        tenantId, AlertEvent.AlertType.OVERLAP)
                .stream().map(IntegrationsController::toView).toList());
        return "pages/settings/integrations";
    }

    // --- Telegram-алерты

    @PostMapping("/telegram")
    public String saveTelegram(@RequestParam(required = false) String tgToken,
                               @RequestParam(required = false) String tgChatId,
                               HttpServletRequest request,
                               RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        String token = tgToken == null ? "" : tgToken.trim();
        String chatId = tgChatId == null ? "" : tgChatId.trim();

        if (!token.isEmpty() && !TelegramService.isValidToken(token)) {
            redirect.addFlashAttribute("error",
                    "Токен не похож на токен бота. Скопируйте его из @BotFather целиком, вида 123456789:AA…");
            return "redirect:/settings/integrations";
        }
        if (!chatId.isEmpty() && !TelegramService.isValidChatId(chatId)) {
            redirect.addFlashAttribute("error",
                    "ID чата — это число (у групп с минусом). Проще нажать «Найти чат».");
            return "redirect:/settings/integrations";
        }

        if (!token.isEmpty()) {
            settings.setEncryptedValue(tenantId, TelegramService.KEY_TOKEN, token);
        }
        settings.setValue(tenantId, TelegramService.KEY_CHAT_ID, chatId);
        rememberBaseUrl(tenantId, request);

        redirect.addFlashAttribute("success", "Настройки Telegram сохранены");
        return "redirect:/settings/integrations";
    }

    /** Хост написал боту /start — находим этот чат, сохраняем и шлём в него проверочное сообщение. */
    @PostMapping("/telegram/detect")
    public String detectTelegramChat(HttpServletRequest request, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        try {
            Optional<TelegramService.Chat> chat = telegram.detectChat(tenantId);
            if (chat.isEmpty()) {
                redirect.addFlashAttribute("error",
                        "Чат не найден. Откройте бота в Telegram, отправьте ему /start и нажмите «Найти чат» ещё раз.");
                return "redirect:/settings/integrations";
            }
            settings.setValue(tenantId, TelegramService.KEY_CHAT_ID, chat.get().id());
            rememberBaseUrl(tenantId, request);
            telegram.send(tenantId, "OptiRent подключён. Сюда будут приходить уведомления о пересечениях броней и сбоях каналов.");
            redirect.addFlashAttribute("success", "Чат найден: " + chat.get().title() + ". Проверочное сообщение отправлено.");
        } catch (Exception e) {
            redirect.addFlashAttribute("error", "Не удалось найти чат: " + e.getMessage());
        }
        return "redirect:/settings/integrations";
    }

    @PostMapping("/telegram/test")
    public String testTelegram(RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        TelegramService.SendResult result = telegram.send(tenantId,
                "Проверка связи: уведомления OptiRent работают.");
        if (result.ok()) {
            redirect.addFlashAttribute("success", "Сообщение отправлено — проверьте Telegram");
        } else {
            redirect.addFlashAttribute("error", "Не отправлено: " + result.error());
        }
        return "redirect:/settings/integrations";
    }

    /** Прогон детектора без ожидания планировщика. */
    @PostMapping("/telegram/check")
    public String checkAlertsNow(RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        try {
            alertScheduler.checkTenant(tenantId);
            long open = alertRepo.findByTenantIdAndResolvedAtIsNull(tenantId).stream()
                    .filter(a -> a.getAlertType() != AlertEvent.AlertType.OVERLAP)
                    .count();
            redirect.addFlashAttribute("success", open == 0
                    ? "Проверка выполнена: пересечений и сбоев каналов нет"
                    : "Проверка выполнена. Открытых проблем: " + open);
        } catch (Exception e) {
            log.warn("Manual alert check failed for tenant {}: {}", tenantId, e.getMessage());
            redirect.addFlashAttribute("error", "Проверка не выполнена: " + e.getMessage());
        }
        return "redirect:/settings/integrations";
    }

    @PostMapping("/telegram/clear")
    public String clearTelegram(RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        settings.setEncryptedValue(tenantId, TelegramService.KEY_TOKEN, null);
        settings.setValue(tenantId, TelegramService.KEY_CHAT_ID, "");
        redirect.addFlashAttribute("success", "Telegram отключён");
        return "redirect:/settings/integrations";
    }

    /** Планировщик работает вне запроса и сам адрес сервиса не знает — запоминаем его здесь. */
    private void rememberBaseUrl(Long tenantId, HttpServletRequest request) {
        String baseUrl = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        settings.setValue(tenantId, TelegramService.KEY_BASE_URL, baseUrl);
    }

    private static AlertView toView(AlertEvent a) {
        boolean conflict = a.getAlertType() == AlertEvent.AlertType.CONFLICT;
        boolean open = a.getResolvedAt() == null;
        String status;
        String statusCss;
        if (!open) {
            status = "Закрыт " + a.getResolvedAt().format(ALERT_TIME);
            statusCss = "tag tag--muted";
        } else if (a.getNotifiedAt() != null) {
            status = "Открыт, отправлен";
            statusCss = "tag tag--red";
        } else {
            status = "Открыт, не отправлен";
            statusCss = "tag tag--amber";
        }
        return new AlertView(
                a.getFirstSeenAt().format(ALERT_TIME),
                conflict ? "Пересечение" : "Сбой канала",
                a.getMessage(), status, statusCss);
    }

    public record AlertView(String time, String type, String message,
                            String status, String statusCss) {}
}
