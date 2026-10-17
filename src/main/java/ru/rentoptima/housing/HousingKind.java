package ru.rentoptima.housing;

import java.util.Locale;

/**
 * Тип жилья объекта ({@code properties.kind}, V40) и его слово во всех формах. Хозяин выбирает тип
 * в мастере («Что вы сдаёте?») и меняет в «Объектах». Новый тип — значение здесь, в ограничении
 * {@code properties_kind_check} (новая миграция) и в {@code HousingTest}.
 */
public enum HousingKind {

    APARTMENT("Квартира", new Housing("квартира", "квартиры", "квартиру", "квартиры", "квартир",
            "ваша квартира", "категория", "категории", "номеров", false)),
    APARTMENTS("Апартаменты", new Housing("апартаменты", "апартаментов", "апартаменты", "апартаменты",
            "апартаментов", "ваши апартаменты", "категория", "категории", "номеров", false)),
    HOUSE("Дом", new Housing("дом", "дома", "дом", "дома", "домов",
            "ваш дом", "категория", "категории", "номеров", false)),
    /** Мини-отель, гостевой дом: объект — «гостиница», категории — «типы номеров», единицы — «номера». */
    ROOMS("Комнаты или номера — мини-отель, гостевой дом", new Housing("гостиница", "гостиницы", "гостиницу",
            "гостиницы", "гостиниц", "ваша гостиница", "тип номера", "типы номеров", "номеров", true)),
    OTHER("Другое", new Housing("жильё", "жилья", "жильё", "объекты", "объектов",
            "ваше жильё", "категория", "категории", "номеров", false));

    /** Подпись варианта в «Что вы сдаёте?». */
    public final String label;
    public final Housing housing;

    HousingKind(String label, Housing housing) {
        this.label = label;
        this.housing = housing;
    }

    public String getLabel() {
        return label;
    }

    public String getCode() {
        return name();
    }

    /** Код из базы или формы; пусто и незнакомое — квартира (так было до V40). */
    public static HousingKind of(String code) {
        if (code == null || code.isBlank()) return APARTMENT;
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return APARTMENT;
        }
    }
}
