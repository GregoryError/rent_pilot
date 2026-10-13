package ru.rentoptima.onboarding;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.rentoptima.security.AuthContext;

import java.util.Optional;

/**
 * Куда ведёт вход: новичок с незаконченным мастером попадает в мастер, а не в пустую
 * шахматку. Перехватываются только страницы, на которые человек приходит после входа и
 * регистрации; с остальных его никто не уводит. «Пропустить, настрою сам» выключает
 * перехват насовсем.
 * <p>
 * {@code ?onboarding=reset} на шахматке или главной сбрасывает состояние онбординга
 * текущего пользователя — для проверки. Чужое состояние так не сбросить: берётся id
 * вошедшего.
 */
@Configuration
@RequiredArgsConstructor
public class OnboardingInterceptor implements WebMvcConfigurer, HandlerInterceptor {

    private final OnboardingService onboarding;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/calendar/grid", "/dashboard", "/welcome");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!"GET".equals(request.getMethod())) return true;
        Long userId = AuthContext.userId();
        if (userId == null) return true;

        if ("reset".equals(request.getParameter("onboarding"))) {
            onboarding.restart(userId, AuthContext.tenantId());
            response.sendRedirect(request.getContextPath() + "/onboarding");
            return false;
        }
        Optional<OnboardingState> state = onboarding.find(userId);
        if (state.isPresent() && state.get().wizardActive()) {
            response.sendRedirect(request.getContextPath() + "/onboarding");
            return false;
        }
        return true;
    }
}
