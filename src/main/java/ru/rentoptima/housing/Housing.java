package ru.rentoptima.housing;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Слово для жилья во всех нужных интерфейсу формах. Тексты с подстановкой пишутся шаблонами с
 * метками ({@code «Добавить %acc%»}, {@code «Цена %gen%»}) и заполняются {@link #fill} — фразы не
 * склеиваются из строк в коде, а все формы, включая согласование («ваша квартира» / «ваш дом» /
 * «ваши апартаменты»), заданы в словаре явно.
 * <p>
 * Метки: {@code %nom%} (им.), {@code %gen%} (род.), {@code %acc%} (вин.), {@code %pl%} (им. мн.),
 * {@code %plGen%} (род. мн.), {@code %your%} («ваша квартира»), {@code %cat%} / {@code %cats%}
 * (категория объекта: «категория» / «тип номера», мн.), {@code %units%} («номеров» — род. мн.
 * единицы внутри категории). С заглавной буквы — та же метка с заглавной: {@code %Nom%}, {@code %Your%}.
 *
 * @param rooms мини-отель или гостевой дом: категории — «типы номеров», единицы — «номера»
 */
public record Housing(String nom, String gen, String acc, String pl, String plGen, String your,
                      String cat, String cats, String units, boolean rooms) {

    /**
     * Жильё вообще: объекта ещё нет или у хозяина объекты разных типов. В одиночных фразах —
     * «жильё» («Добавить жильё»), в списках — «объекты».
     */
    public static final Housing GENERIC = new Housing("жильё", "жилья", "жильё", "объекты", "объектов",
            "ваше жильё", "категория", "категории", "номеров", false);

    private static final Pattern MARK = Pattern.compile("%([A-Za-z]+)%");

    /** Подставить формы в шаблон. Неизвестная метка — ошибка: опечатка не должна дойти до экрана. */
    public String fill(String template) {
        if (template == null || template.indexOf('%') < 0) return template;
        Matcher m = MARK.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String form = form(Character.toLowerCase(key.charAt(0)) + key.substring(1));
            if (form == null) throw new IllegalArgumentException("Неизвестная метка %" + key + "% в тексте: " + template);
            if (Character.isUpperCase(key.charAt(0))) form = capitalize(form);
            m.appendReplacement(out, Matcher.quoteReplacement(form));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String form(String key) {
        return switch (key) {
            case "nom" -> nom;
            case "gen" -> gen;
            case "acc" -> acc;
            case "pl" -> pl;
            case "plGen" -> plGen;
            case "your" -> your;
            case "cat" -> cat;
            case "cats" -> cats;
            case "units" -> units;
            default -> null;
        };
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(new Locale("ru")) + s.substring(1);
    }
}
