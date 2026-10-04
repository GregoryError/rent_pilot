package ru.rentoptima.service;

import ru.rentoptima.entity.Channel;

import java.util.List;

/**
 * Палитра цветов каналов для шахматки.
 * <p>
 * Цвета подобраны так, чтобы поверх любого читался белый текст (контраст с белым
 * не ниже 3.4), поэтому произвольный цвет выбрать нельзя — только из списка.
 * Значение из списка подставляется в inline-style плитки, так что проверка
 * {@link #contains} — ещё и защита от мусора в атрибуте style.
 */
public final class ChannelPalette {

    public static final List<String> COLORS = List.of(
            "#8635F6", "#6D3BEF", "#5B4BE8", "#3F5FE8", "#2472E0", "#0B84D8",
            "#0094B5", "#008F9C", "#00917A", "#0E9558", "#3B9A2F", "#6B9415",
            "#A8860A", "#B5651D", "#D95F1A", "#E5552B", "#E53E4E", "#D1495B",
            "#DB2F74", "#B8336A", "#C92F9B", "#AE35C4", "#9B4DDB", "#7B4FA8");

    /** Занятость из RealtyCalendar: канала у таких броней нет, цвет фиксированный. */
    public static final String RC_COLOR = "#5B6475";

    private ChannelPalette() {
    }

    public static boolean contains(String color) {
        return color != null && COLORS.contains(color.toUpperCase());
    }

    /** Цвет канала: выбранный хостом либо, пока не выбран, стабильный цвет по id. */
    public static String colorOf(Channel channel) {
        if (contains(channel.getColor())) return channel.getColor().toUpperCase();
        long id = channel.getId() == null ? 0 : channel.getId();
        return COLORS.get((int) (Math.abs(id * 5) % COLORS.size()));
    }

    /** Первая буква названия — подпись на плитке шахматки. */
    public static String letterOf(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).toUpperCase();
    }
}
