package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.repository.CalendarBlockRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Уведомления по заявкам и броням с виджета: хозяину — в Telegram, гостю — письмом
 * (если настроен SMTP и гость оставил адрес). Сбой уведомления никогда не ломает
 * саму бронь: методы вызываются после её транзакции и исключений не бросают.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WidgetNotifier {

    /** За сколько до истечения заявки напомнить хозяину. */
    static final Duration REMIND_BEFORE = Duration.ofHours(2);

    private static final String DEFAULT_BASE_URL = "https://optirent.ru";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final TelegramService telegram;
    private final EmailService email;
    private final SettingsService settings;
    private final CalendarBlockRepository blockRepo;
    private final BookingRepository bookingRepo;
    private final BookingWidgetRepository widgetRepo;

    /** Новая заявка (REQUEST) или бронь (INSTANT). */
    public void created(BookingWidget w, Booking b, LocalDateTime holdExpiresAt, String baseUrl) {
        boolean instant = holdExpiresAt == null;
        try {
            if (telegram.isConfigured(w.getTenantId())) {
                StringBuilder sb = new StringBuilder(instant
                        ? "✅ OptiRent: новая бронь со страницы бронирования\n"
                        : "🛎 OptiRent: новая заявка на бронь\n");
                sb.append(summary(w, b));
                if (instant) {
                    sb.append("Даты уже заняты в шахматке и уйдут на площадки.\n");
                    sb.append("Брони: ").append(baseUrl).append("/bookings/pending");
                } else {
                    sb.append("Даты держатся до ").append(holdExpiresAt.format(TIME)).append(".\n");
                    sb.append("Подтвердить или отклонить: ").append(baseUrl).append("/bookings/pending");
                }
                telegram.send(w.getTenantId(), sb.toString());
            }
        } catch (Exception e) {
            log.warn("Не удалось уведомить хозяина о заявке с виджета {}: {}", w.getId(), e.getMessage());
        }
        guest(w, b, instant ? Event.BOOKED : Event.REQUESTED);
    }

    public void confirmed(Booking b) {
        widgetOf(b, e -> guest(e, b, Event.BOOKED));
    }

    public void declined(Booking b) {
        widgetOf(b, e -> guest(e, b, Event.DECLINED));
    }

    public void cancelled(Booking b) {
        widgetOf(b, e -> guest(e, b, Event.CANCELLED));
    }

    /**
     * Напоминает хозяину о заявках, которые истекут в ближайшие два часа. Заявкам с
     * резервом короче четырёх часов не напоминаем: сообщение о самой заявке было только что.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    @Transactional
    public void remindExpiring() {
        LocalDateTime now = LocalDateTime.now();
        for (CalendarBlock hold : blockRepo.findByExpiresAtBetween(now, now.plus(REMIND_BEFORE))) {
            if (!hold.isWidgetOwned() || hold.getCancelledAt() != null || hold.getExternalUid() == null) continue;
            Booking b = bookingRepo.findByChannelIdAndExternalId(hold.getChannelId(), hold.getExternalUid())
                    .orElse(null);
            if (b == null || !WidgetBookingService.STATUS_PENDING.equals(b.getStatus())
                    || b.getHoldReminderSentAt() != null) {
                continue;
            }
            // Отметку ставим до отправки и при любом исходе: лучше одно пропущенное
            // напоминание, чем повтор каждые пять минут.
            b.setHoldReminderSentAt(now);
            bookingRepo.save(b);
            if (!shouldRemind(b.getCreatedAt(), hold.getExpiresAt())) continue;
            if (!telegram.isConfigured(hold.getTenantId())) continue;

            String title = widgetRepo.findByTenantIdAndActiveTrueOrderByCreatedAtAsc(hold.getTenantId()).stream()
                    .filter(w -> w.getChannelId().equals(hold.getChannelId()))
                    .map(BookingWidget::getTitle).findFirst().orElse("Страница бронирования");
            long minutes = Math.max(1, Duration.between(now, hold.getExpiresAt()).toMinutes());
            telegram.send(hold.getTenantId(), "⏰ OptiRent: заявка на бронь скоро истечёт\n"
                    + title + "\n"
                    + b.getCheckIn().format(DAY) + " — " + b.getCheckOut().format(DAY)
                    + ", гость " + (b.getGuestName() == null ? "—" : b.getGuestName())
                    + ", " + b.getGuestPhone() + "\n"
                    + "Осталось " + remaining(minutes) + " — потом даты освободятся сами.\n"
                    + "Подтвердить или отклонить: " + baseUrl(hold.getTenantId()) + "/bookings/pending");
        }
    }

    /** Напоминание имеет смысл, только если резерв дольше двух периодов напоминания. */
    static boolean shouldRemind(LocalDateTime createdAt, LocalDateTime expiresAt) {
        return Duration.between(createdAt, expiresAt).compareTo(REMIND_BEFORE.multipliedBy(2)) >= 0;
    }

    static String remaining(long minutes) {
        if (minutes < 60) return minutes + " мин";
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " ч" : hours + " ч " + rest + " мин";
    }

    private String summary(BookingWidget w, Booking b) {
        StringBuilder sb = new StringBuilder();
        sb.append(w.getTitle()).append(" · № ").append(b.getPublicCode()).append("\n");
        sb.append(b.getCheckIn().format(DAY)).append(" — ").append(b.getCheckOut().format(DAY))
                .append(", ночей: ").append(b.getNights())
                .append(", гостей: ").append(b.getGuestCount()).append("\n");
        if (b.getAmount() != null && b.getAmount().signum() > 0) {
            sb.append("Сумма: ").append(rub(b.getAmount())).append(" ₽");
            if (b.getPrepaymentAmount() != null && b.getPrepaymentAmount().signum() > 0) {
                sb.append(", предоплата ").append(rub(b.getPrepaymentAmount())).append(" ₽");
            }
            sb.append("\n");
        }
        sb.append("Гость: ").append(b.getGuestName() == null ? "—" : b.getGuestName())
                .append(", ").append(b.getGuestPhone());
        if (b.getGuestEmail() != null) sb.append(", ").append(b.getGuestEmail());
        sb.append("\n");
        if (b.getNotes() != null) sb.append(b.getNotes()).append("\n");
        if (b.getUtmSource() != null) sb.append("Источник: ").append(b.getUtmSource()).append("\n");
        return sb.toString();
    }

    private enum Event { REQUESTED, BOOKED, DECLINED, CANCELLED }

    private void guest(BookingWidget w, Booking b, Event event) {
        try {
            if (b.getGuestEmail() == null || !email.smtpConfigured()) return;
            boolean en = "en".equals(b.getLocale());
            String dates = b.getCheckIn().format(DAY) + " — " + b.getCheckOut().format(DAY);
            String subject;
            StringBuilder text = new StringBuilder();
            switch (event) {
                case REQUESTED -> {
                    subject = en ? "Booking request received: " + w.getTitle() : "Заявка получена: " + w.getTitle();
                    text.append(en ? "We have passed your request to the host. They will contact you to confirm it.\n\n"
                            : "Мы передали вашу заявку хозяину. Он свяжется с вами, чтобы подтвердить бронь.\n\n");
                }
                case BOOKED -> {
                    subject = en ? "Booking confirmed: " + w.getTitle() : "Бронь подтверждена: " + w.getTitle();
                    text.append(en ? "Your booking is confirmed.\n\n" : "Ваша бронь подтверждена.\n\n");
                }
                case DECLINED -> {
                    subject = en ? "Booking request declined: " + w.getTitle() : "Заявка отклонена: " + w.getTitle();
                    text.append(en ? "Unfortunately, the host could not accept your request.\n\n"
                            : "К сожалению, хозяин не смог принять вашу заявку.\n\n");
                }
                default -> {
                    subject = en ? "Booking cancelled: " + w.getTitle() : "Бронь отменена: " + w.getTitle();
                    text.append(en ? "The host has cancelled your booking.\n\n" : "Хозяин отменил вашу бронь.\n\n");
                }
            }
            text.append(w.getTitle()).append("\n");
            text.append(en ? "Booking no. " : "Номер брони: ").append(b.getPublicCode()).append("\n");
            text.append(en ? "Dates: " : "Даты: ").append(dates).append("\n");
            if (event == Event.REQUESTED || event == Event.BOOKED) {
                text.append(en ? "Check-in from " : "Заезд с ").append(w.getCheckinTime())
                        .append(en ? ", check-out by " : ", выезд до ").append(w.getCheckoutTime()).append("\n");
                if (b.getAmount() != null && b.getAmount().signum() > 0
                        && Boolean.TRUE.equals(w.getShowPrice())) {
                    text.append(en ? "Total: " : "Сумма: ").append(rub(b.getAmount())).append(" ₽\n");
                }
                if (w.getContactPhone() != null) {
                    text.append(en ? "Host phone: " : "Телефон хозяина: ").append(w.getContactPhone()).append("\n");
                }
            }
            email.send(b.getGuestEmail(), subject, text.toString());
        } catch (Exception e) {
            log.warn("Не удалось отправить письмо гостю по брони {}: {}", b.getId(), e.getMessage());
        }
    }

    private void widgetOf(Booking b, java.util.function.Consumer<BookingWidget> action) {
        try {
            widgetRepo.findByTenantIdAndActiveTrueOrderByCreatedAtAsc(b.getTenant().getId()).stream()
                    .filter(w -> w.getChannelId().equals(b.getChannelId()))
                    .findFirst().ifPresent(action);
        } catch (Exception e) {
            log.warn("Не удалось уведомить гостя по брони {}: {}", b.getId(), e.getMessage());
        }
    }

    private String baseUrl(Long tenantId) {
        String url = settings.getValue(tenantId, TelegramService.KEY_BASE_URL);
        return url == null || url.isBlank() ? DEFAULT_BASE_URL : url;
    }

    private static String rub(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
