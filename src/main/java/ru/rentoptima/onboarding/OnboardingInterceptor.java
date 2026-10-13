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
    private final OnboardingFacts facts;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/calendar/grid", "/dashboard", "/welcome",
                "/settings/widgets/*/card/print");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!"GET".equals(request.getMethod())) return true;
        Long userId = AuthContext.userId();
        if (userId == null) return true;

        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Лист печати карточки открыт — пункт чек-листа «Распечатать карточку» выполнен
        if (path.endsWith("/card/print")) {
            onboarding.find(userId).filter(s -> !OnboardingService.legacy(s))
                    .ifPresent(s -> onboarding.flag(s, OnboardingChecklist.CARD_PRINTED, true));
            return true;
        }

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
        // Шахматка открыта, и в ней уже есть бронь с площадки — «Увидеть первую бронь» выполнено
        if (path.equals("/calendar/grid") && state.isPresent() && !OnboardingService.legacy(state.get())
                && !state.get().getData().path(OnboardingChecklist.SEEN_BOOKING).asBoolean(false)
                && facts.of(state.get().getTenantId()).booking()) {
            onboarding.flag(state.get(), OnboardingChecklist.SEEN_BOOKING, true);
        }
        return true;
    }
}
