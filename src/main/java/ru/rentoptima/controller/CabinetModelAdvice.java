package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.UnitVocabulary;

/**
 * Общее для страниц кабинета:
 * <ul>
 *   <li>{@code hasProperties} — есть ли у хозяина хоть одна квартира: без неё меню короткое
 *       (Главная, Добавить квартиру, Помощь);</li>
 *   <li>{@code simpleUnits} — у хозяина один объект на одну единицу, и шаблоны не показывают
 *       понятие «категория номеров» ({@link UnitVocabulary});</li>
 *   <li>{@code housing} — слово для жилья хозяина в нужных формах
 *       ({@link ru.rentoptima.housing.HousingVocabulary}).</li>
 * </ul>
 */
@ControllerAdvice
@RequiredArgsConstructor
public class CabinetModelAdvice {

    private final UnitVocabulary vocabulary;
    private final ru.rentoptima.housing.HousingVocabulary housingVocabulary;

    private static boolean page(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "GET".equals(request.getMethod()) && !path.startsWith("/api/") && !path.startsWith("/calendar/grid/action");
    }

    @ModelAttribute("hasProperties")
    public boolean hasProperties(HttpServletRequest request) {
        Long tenantId = AuthContext.tenantIdOrNull();
        return tenantId != null && page(request) && vocabulary.hasProperties(tenantId);
    }

    /**
     * Слово для жилья хозяина в нужных формах: {@code ${housing.fill('Добавить %acc%')}},
     * {@code ${housing.your}}. Объектов нет или они разных типов — «жильё» / «объекты».
     */
    @ModelAttribute("housing")
    public ru.rentoptima.housing.Housing housing() {
        return housingVocabulary.forTenant(AuthContext.tenantIdOrNull());
    }

    @ModelAttribute("simpleUnits")
    public boolean simpleUnits(HttpServletRequest request) {
        Long tenantId = AuthContext.tenantIdOrNull();
        return tenantId != null && page(request) && vocabulary.simple(tenantId);
    }
}
