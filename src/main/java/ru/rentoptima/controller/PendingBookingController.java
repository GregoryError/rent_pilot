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
import ru.rentoptima.service.WidgetBookingService;

import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Заявки с виджета бронирования: хост подтверждает или отклоняет вручную. */
@Controller
@RequestMapping("/bookings/pending")
@RequiredArgsConstructor
public class PendingBookingController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final BookingRepository bookingRepo;
    private final BookingWidgetRepository widgetRepo;
    private final WidgetBookingService widgets;

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
                    ? "резерв снят" : "даты держатся до " + expires.format(TIME), "tag tag--amber"));
        }
        List<RequestRow> history = new ArrayList<>();
        for (Booking b : bookingRepo.findTop20ByTenantIdAndDataSourceAndStatusNotOrderByCreatedAtDesc(
                tenantId, WidgetBookingService.DATA_SOURCE, WidgetBookingService.STATUS_PENDING)) {
            String status = switch (b.getStatus()) {
                case WidgetBookingService.STATUS_BOOKED -> "Подтверждена";
                case WidgetBookingService.STATUS_DECLINED -> "Отклонена";
                case WidgetBookingService.STATUS_EXPIRED -> "Истекла без ответа";
                default -> b.getStatus();
            };
            String css = WidgetBookingService.STATUS_BOOKED.equals(b.getStatus())
                    ? "tag tag--green" : "tag tag--muted";
            history.add(row(b, titles, status, css));
        }

        model.addAttribute("activePage", "pending");
        model.addAttribute("pending", pending);
        model.addAttribute("history", history);
        return "pages/bookings/pending";
    }

    @PostMapping("/{id}/confirm")
    public String confirm(@PathVariable Long id, RedirectAttributes redirect) {
        if (widgets.confirm(AuthContext.tenantId(), id)) {
            redirect.addFlashAttribute("success", "Бронь подтверждена — даты заняты в шахматке и уйдут на площадки");
        } else {
            redirect.addFlashAttribute("error", "Заявка не найдена или уже обработана");
        }
        return "redirect:/bookings/pending";
    }

    @PostMapping("/{id}/decline")
    public String decline(@PathVariable Long id, RedirectAttributes redirect) {
        if (widgets.decline(AuthContext.tenantId(), id)) {
            redirect.addFlashAttribute("success", "Заявка отклонена, даты снова свободны");
        } else {
            redirect.addFlashAttribute("error", "Заявка не найдена или уже обработана");
        }
        return "redirect:/bookings/pending";
    }

    private static RequestRow row(Booking b, Map<Long, String> titles, String status, String statusCss) {
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
                b.getNotes() == null ? "" : b.getNotes(),
                b.getCreatedAt().format(TIME),
                status, statusCss);
    }

    public record RequestRow(Long id, String widgetTitle, String dates, int nights, int guests,
                             String amount, String guestName, String guestPhone, String notes,
                             String createdAt, String status, String statusCss) {}
}
