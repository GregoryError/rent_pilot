package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.WidgetPhoto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Настройки виджета в том виде, в каком их получает компонент: и ответом
 * {@code GET /api/widget/{slug}/config}, и встроенными прямо в страницу бронирования
 * {@code /b/{slug}} — там виджет рисуется сразу, не дожидаясь запроса.
 */
@Service
@RequiredArgsConstructor
public class WidgetConfigService {

    private final WidgetPhotoService photos;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param baseUrl адрес приложения — на чужом сайте виджет берёт фото с нашего домена
     */
    public Map<String, Object> config(BookingWidget w, String baseUrl) {
        LocalDate today = LocalDate.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slug", w.getSlug());
        body.put("title", w.getTitle());
        body.put("mode", w.getMode());
        body.put("currency", "RUB");
        body.put("today", today.toString());
        body.put("maxDate", WidgetBookingService.maxDate(w, today).toString());
        body.put("minNights", w.getMinNights());
        body.put("maxNights", w.getMaxNights());
        body.put("maxGuests", w.getMaxGuests());
        body.put("petsAllowed", Boolean.TRUE.equals(w.getPetsAllowed()));
        body.put("checkinTime", w.getCheckinTime().toString());
        body.put("checkoutTime", w.getCheckoutTime().toString());
        body.put("holdMinutes", w.getHoldMinutes());
        body.put("showPrice", Boolean.TRUE.equals(w.getShowPrice()));
        body.put("showPoweredBy", Boolean.TRUE.equals(w.getShowPoweredBy()));
        body.put("theme", w.getTheme());
        body.put("weeklyDiscountPercent", w.getWeeklyDiscountPercent());
        body.put("monthlyDiscountPercent", w.getMonthlyDiscountPercent());
        body.put("prepaymentPercent", w.getPrepaymentPercent());
        body.put("description", w.getDescription());
        body.put("rules", w.getRules());
        body.put("cancellationPolicy", w.getCancellationPolicy());
        body.put("addressHint", w.getAddressHint());
        // Сначала загруженные фото (с вариантами и заглушкой), затем ссылки, заданные раньше
        List<Map<String, Object>> gallery = new ArrayList<>();
        for (WidgetPhoto p : photos.list(w.getId())) gallery.add(photos.view(p, baseUrl));
        for (String url : photoLinks(w)) gallery.add(Map.of("src", url));
        body.put("photos", gallery);
        ObjectNode layout = WidgetLayout.normalize(w.getConfigJson());
        body.put("layout", layout);
        body.put("metrikaId", w.getMetrikaId());
        body.put("amenities", WidgetLayout.amenities(w.getAmenities()));
        body.put("mapUrl", WidgetLayout.mapUrl(w.getMapUrl()));
        // Контакты до брони отдаём, только если хозяин оставил блок «Контакты» видимым
        if (!WidgetLayout.hidden(layout, "contacts")) {
            Map<String, Object> contacts = new LinkedHashMap<>();
            contacts.put("phone", w.getContactPhone());
            contacts.put("telegram", w.getContactTelegram());
            contacts.put("whatsapp", w.getContactWhatsapp());
            body.put("contacts", contacts);
        }
        return body;
    }

    /**
     * Настройки для вставки в {@code <script type="application/json">}. Символ «<»
     * экранируется: текст хозяина с «</script>» не должен закрыть тег и стать разметкой.
     */
    public String inlineJson(BookingWidget w, String baseUrl) {
        try {
            return json.writeValueAsString(config(w, baseUrl))
                    .replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        } catch (Exception e) {
            return "null";
        }
    }

    /** Картинка для превью ссылки в мессенджере: первое загруженное фото, иначе первая ссылка. */
    public String shareImage(BookingWidget w, String baseUrl) {
        List<WidgetPhoto> uploaded = photos.list(w.getId());
        if (!uploaded.isEmpty()) return photos.previewUrl(uploaded.get(0), baseUrl);
        List<String> links = photoLinks(w);
        return links.isEmpty() ? null : links.get(0);
    }

    /** Фото, заданные ссылками (старый способ, {@code photos_json}): только https. */
    public static List<String> photoLinks(BookingWidget w) {
        List<String> links = new ArrayList<>();
        JsonNode node = w.getPhotosJson();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                if (n.isTextual() && n.asText().startsWith("https://")) links.add(n.asText());
            }
        }
        return links;
    }
}
