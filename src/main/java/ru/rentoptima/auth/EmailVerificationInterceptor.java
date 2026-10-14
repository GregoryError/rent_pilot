package ru.rentoptima.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.rentoptima.security.AuthContext;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Срок без подтверждения почты вышел — кабинет закрыт экраном /verify-email. Открыты сам
 * экран, повторное письмо, смена адреса, выход и всё публичное (страницы бронирования
 * хозяина работают как работали).
 */
@Configuration
@RequiredArgsConstructor
public class EmailVerificationInterceptor implements WebMvcConfigurer, HandlerInterceptor {

    static final List<String> OPEN = List.of(
            "/verify", "/account/verify/", "/account/email", "/logout", "/login", "/error",
            "/api/", "/b/", "/q/", "/f/", "/book/", "/widget", "/w.js", "/ical/", "/legal/", "/feedback/",
            "/housekeeper/", "/media/", "/css/", "/js/", "/fonts/", "/img/", "/icons/", "/libs/", "/webjars/",
            "/favicon", "/apple-touch-icon", "/site.webmanifest", "/robots.txt", "/sitemap.xml");

    private final EmailVerificationService verification;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Long userId = AuthContext.userId();
        if (userId == null) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (open(path)) return true;
        boolean locked = verification.status(userId).map(s -> s.locked(OffsetDateTime.now())).orElse(false);
        if (!locked) return true;
        response.sendRedirect(request.getContextPath() + "/verify-email");
        return false;
    }

    /** Префиксы открытых адресов: «/verify» покрывает и ссылку из письма, и экран /verify-email. */
    static boolean open(String path) {
        for (String p : OPEN) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }
}
