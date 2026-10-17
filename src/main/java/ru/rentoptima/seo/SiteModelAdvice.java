package ru.rentoptima.seo;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** {@code ${site}} в шаблонах: коды подтверждения поисковиков, окружение; {@code ${legalOperator}}. */
@ControllerAdvice
@RequiredArgsConstructor
public class SiteModelAdvice {

    private final SiteSettings site;

    @ModelAttribute("site")
    public SiteSettings site() {
        return site;
    }

    /** Оператор персональных данных — подвал сайта и страницы документов. */
    @ModelAttribute("legalOperator")
    public ru.rentoptima.legal.Operator.View legalOperator() {
        return ru.rentoptima.legal.Operator.VIEW;
    }
}
