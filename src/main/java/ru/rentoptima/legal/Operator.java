package ru.rentoptima.legal;

/**
 * Оператор персональных данных сервиса — для подвала, всплывающего окна согласий и
 * запасного текста согласия гостя. В тексты документов эти данные вписаны миграцией V42:
 * меняются здесь — нужна и новая редакция документов.
 */
public final class Operator {

    public static final String FULL_NAME = "Колесников Григорий Вячеславович";
    public static final String SHORT_NAME = "Колесников Г. В.";
    public static final String INN = "470416071381";
    public static final String EMAIL = "kolesnikoff2085@yandex.ru";

    /** Строка подвала: «Колесников Г. В., самозанятый, ИНН … · e-mail». */
    public static final String FOOTER_LINE = SHORT_NAME + ", самозанятый, ИНН " + INN;

    /** Для шаблонов: {@code ${legalOperator.line}}, {@code ${legalOperator.email}} (SiteModelAdvice). */
    public record View(String line, String email) {
    }

    public static final View VIEW = new View(FOOTER_LINE, EMAIL);

    private Operator() {
    }
}
