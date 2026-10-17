package ru.rentoptima.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * QR-код в SVG — для печатной карточки. Генерируется на сервере, без внешних сервисов.
 * <p>
 * Уровень коррекции M (восстанавливается до 15 % повреждений), поле тишины 4 модуля —
 * по стандарту; меньшее поле дешёвые сканеры читают хуже. Код всегда чёрный на белом,
 * независимо от темы карточки: цветной или инвертированный QR на лазерном принтере
 * теряет контраст.
 * <p>
 * SVG векторный и без размеров в пикселях: сколько миллиметров он займёт на бумаге,
 * решает вёрстка карточки.
 */
public final class QrSvg {

    public static final int QUIET_ZONE = 4;

    private QrSvg() {}

    /** Матрица кода вместе с полем тишины. */
    public static BitMatrix matrix(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        try {
            // Размер 0×0 — ZXing отдаёт матрицу «модуль = точка», без растягивания
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw new IllegalArgumentException("Текст не помещается в QR-код", e);
        }
    }

    /**
     * @param label подпись для скринридера (aria-label); спецсимволы экранируются
     */
    public static String svg(String text, String label) {
        BitMatrix m = matrix(text);
        int size = m.getWidth();
        StringBuilder path = new StringBuilder();
        // Тёмные модули строки склеиваются в горизонтальные отрезки: файл в разы короче
        for (int y = 0; y < size; y++) {
            int x = 0;
            while (x < size) {
                if (!m.get(x, y)) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < size && m.get(x, y)) x++;
                path.append('M').append(start).append(' ').append(y)
                        .append('h').append(x - start).append("v1h-").append(x - start).append('z');
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size + "\""
                + " shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"" + escape(label) + "\">"
                + "<rect width=\"" + size + "\" height=\"" + size + "\" fill=\"#fff\"/>"
                + "<path fill=\"#000\" d=\"" + path + "\"/></svg>";
    }

    /**
     * Строка для QR подключения к Wi-Fi: {@code WIFI:T:WPA;S:<сеть>;P:<пароль>;;}.
     * Символы {@code \ ; , : "} в имени сети и пароле экранируются обратной чертой —
     * иначе телефон разберёт строку неверно. Без пароля — открытая сеть ({@code T:nopass}).
     */
    public static String wifi(String ssid, String password) {
        boolean open = password == null || password.isEmpty();
        return "WIFI:T:" + (open ? "nopass" : "WPA") + ";S:" + wifiEscape(ssid) + ";"
                + (open ? "" : "P:" + wifiEscape(password) + ";") + ";";
    }

    static String wifiEscape(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : (value == null ? "" : value).toCharArray()) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
