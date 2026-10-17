package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.UnitVocabulary;

/**
 * Пока у хозяина нет жилья, в меню только «Главная», «Добавить жильё», «Настройки» и «Помощь».
 * Страницы, которых в этом меню нет, без жилья пусты — открытые по старой ссылке или закладке, они
 * ведут в мастер на шаг жилья. «Настройки» (с «Интеграциями») открыты: там уведомления и значения для
 * нового жилья. Шахматка без жилья уводит на «Главную» сама ({@code OccupancyGridController}).
 */
@Configuration
@RequiredArgsConstructor
public class NoPropertyRedirect implements HandlerInterceptor, WebMvcConfigurer {

    static final String WIZARD = "/onboarding?step=property";

    /** Разделы, которых нет в меню без жилья. */
    static final String[] PATHS = {
            "/pricing", "/pricing/**", "/calendar", "/settings/channels", "/settings/channels/**",
            "/settings/widgets", "/settings/widgets/**", "/settings/properties", "/settings/transfer",
            "/bookings/**", "/expenses", "/feedback", "/feedback-admin", "/staff", "/chat"};

    private final UnitVocabulary vocabulary;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(PATHS);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"GET".equals(request.getMethod())) return true;
        Long tenantId = AuthContext.tenantIdOrNull();
        if (tenantId == null || vocabulary.hasProperties(tenantId)) return true;
        response.sendRedirect(request.getContextPath() + WIZARD);
        return false;
    }
}
