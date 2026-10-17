package ru.rentoptima.auth;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.rentoptima.security.AuthContext;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Что подтверждение почты показывает в кабинете: плашку {@code verifyBanner} и флаг
 * {@code pageInactive} — страница бронирования ещё не принимает гостей (для пояснений и
 * неактивных кнопок «Скопировать ссылку» / «Код для сайта»).
 * <p>
 * Плашки нет в мастере первой настройки ({@code inLayout = false}): там своё пояснение на
 * шаге «Страница готова».
 */
@ControllerAdvice
@RequiredArgsConstructor
public class EmailVerificationUi {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM", new Locale("ru"));
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    /**
     * @param email      адрес, на который ушло (уйдёт) письмо
     * @param neverSent  письма ещё не было — кнопка «Отправить письмо», а не «ещё раз»
     * @param deadline   «до 21 октября» — когда без подтверждения закроется кабинет
     * @param back       адрес текущей страницы — вернуться сюда после кнопок плашки
     * @param inLayout   показывать плашку в layout; в мастере — нет, там своё пояснение
     * @param urgent     до закрытия кабинета меньше суток — полосу нельзя скрыть «до завтра»
     */
    public record Banner(String email, boolean neverSent, String deadline, String back, boolean inLayout,
                         boolean urgent) {}

    private final EmailVerificationService verification;

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static boolean cabinetPage(HttpServletRequest request) {
        String path = path(request);
        return "GET".equals(request.getMethod()) && !path.startsWith("/api/") && !path.startsWith("/verify") && !path.startsWith("/calendar/grid/action")
                && !path.endsWith("/frame") && !path.endsWith("/print");
    }

    @ModelAttribute("verifyBanner")
    public Banner banner(HttpServletRequest request) {
        if (AuthContext.userId() == null || !cabinetPage(request)) return null;
        Optional<EmailVerificationService.Status> s = verification.status(AuthContext.userId());
        if (s.isEmpty() || !s.get().pending()) return null;
        String deadline = s.get().deadline() == null ? null
                : s.get().deadline().atZoneSameInstant(MOSCOW).format(DAY);
        String query = request.getQueryString();
        String back = path(request) + (query == null ? "" : "?" + query);
        return new Banner(s.get().email(), s.get().lastSentAt() == null, deadline, back,
                !path(request).startsWith("/onboarding"),
                s.get().deadline() != null && s.get().deadline().isBefore(java.time.OffsetDateTime.now().plusDays(1)));
    }

    @ModelAttribute("pageInactive")
    public boolean pageInactive(HttpServletRequest request) {
        if (AuthContext.tenantIdOrNull() == null || !"GET".equals(request.getMethod()) || path(request).startsWith("/api/")) {
            return false;
        }
        return verification.pageInactive(AuthContext.tenantIdOrNull());
    }
}
