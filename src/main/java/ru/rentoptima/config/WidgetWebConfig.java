package ru.rentoptima.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.rentoptima.service.PublicRateLimiter;

/**
 * Публичные эндпоинты виджета: лимит запросов (60 в минуту с одного IP) и ETag.
 * Адрес клиента берётся из getRemoteAddr() — за nginx он корректен благодаря
 * SERVER_FORWARD_HEADERS_STRATEGY=native в docker-compose.
 */
@Configuration
@RequiredArgsConstructor
public class WidgetWebConfig implements WebMvcConfigurer {

    private static final int PER_MINUTE = 60;

    private final PublicRateLimiter rateLimiter;

    /**
     * ETag для GET-ответов API виджета: при неизменившихся настройках и занятости
     * повторный запрос получает 304 без тела. Вместе с Cache-Control: max-age=60,
     * который ставит WidgetApiController.
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> widgetEtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> bean =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        bean.addUrlPatterns("/api/widget/*");
        return bean;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                     Object handler) {
                // Preflight не считаем: браузер шлёт его перед каждой заявкой с чужого сайта
                if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
                if (rateLimiter.allow("widget:" + request.getRemoteAddr(), PER_MINUTE, 60_000L)) {
                    return true;
                }
                response.setStatus(429);
                return false;
            }
        }).addPathPatterns("/book/**", "/widget/**", "/api/widget/**");
    }
}
