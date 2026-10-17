package ru.rentoptima.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UriUtils;
import ru.rentoptima.legal.AccountConsents;
import ru.rentoptima.security.AuthContext;

import java.nio.charset.StandardCharsets;

/**
 * Пользователь, который не давал согласий по действующим документам (все, кто был до V42), —
 * кабинет закрыт окном согласий /account/legal. Открыты само окно, выход, документы и всё
 * публичное (как у {@link EmailVerificationInterceptor}); страницы бронирования хозяина работают.
 */
@Configuration
@RequiredArgsConstructor
public class LegalConsentInterceptor implements WebMvcConfigurer, HandlerInterceptor {

    static final String PAGE = "/account/legal";

    private final AccountConsents consents;

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
        if (path.startsWith(PAGE) || EmailVerificationInterceptor.open(path)) return true;
        if (!consents.required(userId)) return true;
        String target = request.getContextPath() + PAGE;
        if ("GET".equals(request.getMethod())) {
            target += "?next=" + UriUtils.encodeQueryParam(path, StandardCharsets.UTF_8);
        }
        response.sendRedirect(target);
        return false;
    }
}
