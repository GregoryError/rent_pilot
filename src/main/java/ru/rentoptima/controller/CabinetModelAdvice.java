package ru.rentoptima.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.UnitVocabulary;

/**
 * Общее для страниц кабинета: {@code simpleUnits} — у хозяина одна квартира, и шаблоны не
 * показывают понятие «категория номеров» ({@link UnitVocabulary}).
 */
@ControllerAdvice
@RequiredArgsConstructor
public class CabinetModelAdvice {

    private final UnitVocabulary vocabulary;

    private static boolean page(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "GET".equals(request.getMethod()) && !path.startsWith("/api/") && !path.startsWith("/calendar/grid/action");
    }

    @ModelAttribute("simpleUnits")
    public boolean simpleUnits(HttpServletRequest request) {
        Long tenantId = AuthContext.tenantIdOrNull();
        return tenantId != null && page(request) && vocabulary.simple(tenantId);
    }
}
