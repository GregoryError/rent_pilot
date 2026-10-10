package ru.rentoptima.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.EchoShadowService;
import ru.rentoptima.service.WidgetBookingService;
import ru.rentoptima.service.WidgetBookingService.Outcome;
import ru.rentoptima.service.WidgetNotifier;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Заявки и брони с виджета бронирования: хост подтверждает, отклоняет или отменяет. */
@Controller
@RequestMapping("/bookings/pending")
@RequiredArgsConstructor
public class PendingBookingController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final BookingRepository bookingRepo;
    private final BookingWidgetRepository widgetRepo;
    private final WidgetBookingService widgets;
    private final WidgetNotifier notifier;

    @GetMapping
    public String page(Model model) {
        Long tenantId = AuthContext.tenantId();
        Map<Long, String> titles = new HashMap<>();
        for (BookingWidget w : widgetRepo.findByTenantIdAndActiveTrueOrderByCreatedAtAsc(tenantId)) {
            titles.put(w.getChannelId(), w.getTitle());
        }

        List<RequestRow> pending = new ArrayList<>();
        for (Booking b : bookingRepo.findPendingWidgetRequests(tenantId)) {
            LocalDateTime expires = widgets.holdExpiresAt(b);
            pending.add(row(b, titles, expires == null
                    ? "резерв снят" : "даты держатся до " + expires.format(TIME), "tag tag--amber", false));
        }
        List<RequestRow> history = new ArrayList<>();
        for (Booking b : bookingRepo.findTop20ByTenantIdAndDataSourceAndStatusNotOrderByCreatedAtDesc(
                tenantId, WidgetBookingService.DATA_SOURCE, WidgetBookingService.STATUS_PENDING)) {
            String status = switch (b.getStatus()) {
                case WidgetBookingService.STATUS_BOOKED -> "Подтверждена";
                case WidgetBookingService.STATUS_DECLINED -> "Отклонена";
                case WidgetBookingService.STATUS_EXPIRED -> "Истекла без ответа";
                case WidgetBookingService.STATUS_CANCELLED -> "Отменена";
                default -> b.getStatus();
            };
            String css = WidgetBookingService.STATUS_BOOKED.equals(b.getStatus())
                    ? "tag tag--green" : "tag tag--muted";
            // Отменить можно подтверждённую бронь, которая ещё не закончилась
            boolean cancellable = WidgetBookingService.STATUS_BOOKED.equals(b.getStatus())
                    && !b.getCheckOut().isBefore(java.time.LocalDate.now());
            history.add(row(b, titles, status, css, cancellable));
        }

        model.addAttribute("activePage", "pending");
        model.addAttribute("pending", pending);
        model.addAttribute("history", history);
        return "pages/bookings/pending";
    }

    @PostMapping("/{id}/confirm")
    public String confirm(@PathVariable Long id, RedirectAttributes redirect) {
        Outcome result = widgets.confirm(AuthContext.tenantId(), id);
        if (result.ok()) {
            notifier.confirmed(result.booking());
            redirect.addFlashAttribute("success", "Бронь подтверждена — даты заняты в шахматке и уйдут на площадки");
        } else {
            redirect.addFlashAttribute("error", "Заявка не найдена или уже обработана");
        }
        return "redirect:/bookings/pending";
    }

    @PostMapping("/{id}/decline")
    public String decline(@PathVariable Long id, RedirectAttributes redirect) {
        Outcome result = widgets.decline(AuthContext.tenantId(), id);
        if (result.ok()) {
            notifier.declined(result.booking());
            released(redirect, result, "Заявка отклонена, даты снова свободны", "Заявка отклонена");
        } else {
            redirect.addFlashAttribute("error", "Заявка не найдена или уже обработана");
        }
        return "redirect:/bookings/pending";
    }

    /** Отмена уже подтверждённой брони со страницы бронирования. */
    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes redirect) {
        Outcome result = widgets.cancel(AuthContext.tenantId(), id);
        if (result.ok()) {
            notifier.cancelled(result.booking());
            released(redirect, result, "Бронь отменена, даты снова свободны", "Бронь отменена");
        } else {
            redirect.addFlashAttribute("error", "Бронь не найдена или уже отменена");
        }
        return "redirect:/bookings/pending";
    }

    /**
     * Сообщение после освобождения дат. Если на площадке осталась похожая бронь (тень
     * с признаками настоящей), даты закрыты ею — хозяин получает то же предупреждение,
     * что и при удалении ручной записи.
     */
    private static void released(RedirectAttributes redirect, Outcome result, String freed, String kept) {
        if (result.keptShadows().isEmpty()) {
            redirect.addFlashAttribute("success", freed);
            return;
        }
        redirect.addFlashAttribute("success", kept + ", но даты остаются закрытыми бронью с площадки");
        redirect.addFlashAttribute("echoWarning", EchoShadowService.warning(result.keptShadows()));
    }

    private static RequestRow row(Booking b, Map<Long, String> titles, String status, String statusCss,
                                  boolean cancellable) {
        boolean hasAmount = b.getAmount() != null && b.getAmount().signum() > 0;
        return new RequestRow(
                b.getId(),
                titles.getOrDefault(b.getChannelId(), "Страница бронирования"),
                b.getCheckIn().format(DAY) + " — " + b.getCheckOut().format(DAY),
                b.getNights(),
                b.getGuestCount() == null ? 1 : b.getGuestCount(),
                hasAmount ? b.getAmount().setScale(0, RoundingMode.HALF_UP).toPlainString() + " ₽" : "—",
                b.getGuestName() == null ? "—" : b.getGuestName(),
                b.getGuestPhone() == null ? "—" : b.getGuestPhone(),
                notes(b),
                b.getCreatedAt().format(TIME),
                status, statusCss,
                b.getPublicCode() == null ? "" : "№ " + b.getPublicCode(), cancellable);
    }

    private static String notes(Booking b) {
        List<String> lines = new ArrayList<>();
        if (b.getGuestEmail() != null) lines.add("Email: " + b.getGuestEmail());
        if (b.getNotes() != null && !b.getNotes().isBlank()) lines.add(b.getNotes());
        if (b.getDiscountAmount() != null && b.getDiscountAmount().signum() > 0) {
            lines.add("Скидка: " + b.getDiscountAmount().setScale(0, RoundingMode.HALF_UP).toPlainString() + " ₽");
        }
        if (b.getPrepaymentAmount() != null && b.getPrepaymentAmount().signum() > 0) {
            lines.add("Предоплата: " + b.getPrepaymentAmount().setScale(0, RoundingMode.HALF_UP).toPlainString() + " ₽");
        }
        if (b.getUtmSource() != null) lines.add("Источник: " + b.getUtmSource());
        return String.join("\n", lines);
    }

    public record RequestRow(Long id, String widgetTitle, String dates, int nights, int guests,
                             String amount, String guestName, String guestPhone, String notes,
                             String createdAt, String status, String statusCss,
                             String number, boolean cancellable) {}
}
