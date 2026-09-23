package ru.rentoptima.util;

/**
 * Персональные данные обезличиваются в точке получения — до сохранения куда-либо.
 * Правило: полное ФИО → инициал (первая буква + точка). Телефон → null.
 * Задача: не быть оператором персональных данных.
 */
public final class PdAnonymizer {

    private PdAnonymizer() {}

    /**
     * "Роман Ченин" → "Р."
     * "ILIA KLEMENTEV" → "I."
     * "Ручное закрытие RC" → "Ручное закрытие RC" (не ПД — сохраняется)
     * null / "" → null
     */
    public static String toInitial(String fullName) {
        if (fullName == null) return null;
        String trimmed = fullName.trim();
        if (trimmed.isEmpty()) return null;

        // Технические маркеры (не ПД) — сохраняем как есть
        if (trimmed.startsWith("Ручное закрытие")) return trimmed;

        String first = trimmed.substring(0, 1).toUpperCase();
        return first + ".";
    }

    /** Телефоны никогда не сохраняем. */
    public static String stripPhone(String phone) {
        return null;
    }
}
