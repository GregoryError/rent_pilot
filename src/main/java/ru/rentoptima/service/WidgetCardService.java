package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.PromoCode;
import ru.rentoptima.entity.WidgetCard;
import ru.rentoptima.entity.WidgetPhoto;
import ru.rentoptima.entity.WidgetQrLink;
import ru.rentoptima.repository.PromoCodeRepository;
import ru.rentoptima.repository.WidgetCardRepository;
import ru.rentoptima.repository.WidgetQrLinkRepository;
import ru.rentoptima.util.EncryptionUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Печатная карточка для квартиры: QR на страницу прямого бронирования, промокод на
 * повторный визит, по желанию Wi-Fi и QR обратной связи.
 * <p>
 * QR ведёт не прямо на {@code /b/<slug>}, а на короткий адрес {@code /q/<code>}: код
 * получается проще (крупнее модули — надёжнее читается с бумаги), а куда он ведёт,
 * можно менять уже после печати — сменить промокод или отключить его. Редирект
 * добавляет {@code utm_source}, поэтому переходы с карточки видны в воронке виджета
 * отдельным источником; новых данных о посетителях при этом не собирается.
 */
@Service
@RequiredArgsConstructor
public class WidgetCardService {

    public static final String SOURCE_CARD = "qr_card";
    public static final String SOURCE_MESSAGE = "guest_message";

    /** Буквы и цифры без похожих в начертании: нет 0 и O, 1, l и I. */
    static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    static final int CODE_LENGTH = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final List<String> FORMATS = List.of(WidgetCard.FORMAT_CARD, WidgetCard.FORMAT_A6, WidgetCard.FORMAT_A5);
    private static final List<String> LANGS = List.of("ru", "en", "both");
    public static final List<String> TEXT_KEYS = List.of("title", "subtitle", "caption", "feedback");

    private static final Map<String, Map<String, String>> DEFAULTS = Map.of(
            "ru", Map.ofEntries(
                    Map.entry("title", "Понравилось у нас?"),
                    Map.entry("subtitlePromo", "В следующий раз бронируйте напрямую — скидка {d} по промокоду"),
                    Map.entry("subtitlePlain", "В следующий раз бронируйте напрямую"),
                    Map.entry("caption", "Наведите камеру телефона"),
                    Map.entry("feedback", "Что-то не так или есть пожелания? Напишите нам — исправим"),
                    Map.entry("promo", "Промокод"),
                    Map.entry("wifi", "Wi-Fi"),
                    Map.entry("wifiNet", "Сеть"),
                    Map.entry("wifiPass", "Пароль"),
                    Map.entry("wifiCaption", "Наведите камеру — телефон подключится"),
                    Map.entry("contacts", "Связаться с нами"),
                    Map.entry("message", "Спасибо, что остановились у нас! В следующий раз бронируйте напрямую: {link} — по промокоду {code} скидка {d}."),
                    Map.entry("messagePlain", "Спасибо, что остановились у нас! В следующий раз бронируйте напрямую: {link}")),
            "en", Map.ofEntries(
                    Map.entry("title", "Enjoyed your stay?"),
                    Map.entry("subtitlePromo", "Next time, book directly — {d} off with promo code"),
                    Map.entry("subtitlePlain", "Next time, book directly with us"),
                    Map.entry("caption", "Point your phone camera here"),
                    Map.entry("feedback", "Something wrong or have a suggestion? Tell us — we will fix it"),
                    Map.entry("promo", "Promo code"),
                    Map.entry("wifi", "Wi-Fi"),
                    Map.entry("wifiNet", "Network"),
                    Map.entry("wifiPass", "Password"),
                    Map.entry("wifiCaption", "Point your camera — your phone will connect"),
                    Map.entry("contacts", "Contact us"),
                    Map.entry("message", "Thank you for staying with us! Next time, book directly: {link} — use promo code {code} for {d} off."),
                    Map.entry("messagePlain", "Thank you for staying with us! Next time, book directly: {link}")));

    private static final Map<String, String[]> FONTS = Map.of(
            "inter", new String[] {"Inter", "100 900"},
            "manrope", new String[] {"Manrope", "200 800"},
            "montserrat", new String[] {"Montserrat", "100 900"},
            "lora", new String[] {"Lora", "400 700"});
    private static final String SYSTEM_FONT = "system-ui,-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif";

    private final WidgetCardRepository cardRepo;
    private final WidgetQrLinkRepository linkRepo;
    private final PromoCodeRepository promoRepo;
    private final WidgetPhotoService photos;
    private final EncryptionUtil encryption;
    private final ru.rentoptima.legal.GuestOperators operators;

    // --- Настройки карточки

    /** Сохранённая карточка виджета или новая с настройками по умолчанию (в базу не пишется). */
    public WidgetCard card(BookingWidget w) {
        return cardRepo.findByWidgetIdAndTenantId(w.getId(), w.getTenantId()).orElseGet(() -> {
            WidgetCard card = new WidgetCard();
            card.setWidgetId(w.getId());
            card.setTenantId(w.getTenantId());
            return card;
        });
    }

    /**
     * @param texts тексты из формы: {@code {"ru": {"title": …}, "en": {…}}}; пустые и
     *              совпадающие со значением по умолчанию не сохраняются
     */
    @Transactional
    public WidgetCard save(BookingWidget w, CardForm form) {
        WidgetCard card = card(w);
        card.setFormat(FORMATS.contains(form.format()) ? form.format() : WidgetCard.FORMAT_A6);
        card.setPaper(paperFor(card.getFormat(), form.paper()));
        card.setLang(LANGS.contains(form.lang()) ? form.lang() : "ru");
        card.setMono(form.mono());
        card.setShowContacts(form.showContacts());
        // На визитке для QR обратной связи и фото нет места
        boolean small = WidgetCard.FORMAT_CARD.equals(card.getFormat());
        card.setShowFeedback(form.showFeedback() && !small);

        PromoCode promo = form.promoCodeId() == null ? null
                : promoRepo.findByIdAndTenantId(form.promoCodeId(), w.getTenantId())
                        .filter(p -> p.getWidgetId().equals(w.getId())).orElse(null);
        card.setPromoCodeId(promo == null ? null : promo.getId());

        Long photoId = null;
        if (form.photoId() != null && !small) {
            photoId = photos.list(w.getId()).stream().map(WidgetPhoto::getId)
                    .filter(form.photoId()::equals).findFirst().orElse(null);
        }
        card.setPhotoId(photoId);

        String ssid = trim(form.wifiSsid(), 32);
        card.setWifiSsid(ssid);
        String password = ssid == null ? null : trim(form.wifiPassword(), 63);
        card.setWifiPasswordEnc(password == null ? null : encryption.encrypt(password));

        ObjectNode texts = JsonNodeFactory.instance.objectNode();
        for (String lang : List.of("ru", "en")) {
            ObjectNode node = texts.putObject(lang);
            for (String key : TEXT_KEYS) {
                String value = trim(form.texts().path(lang).path(key).asText(null), 200);
                if (value != null && !value.equals(defaultText(lang, key, promo))) node.put(key, value);
            }
        }
        card.setTextsJson(texts);
        card.setUpdatedAt(LocalDateTime.now());
        cardRepo.save(card);

        // Промокод карточки подставляют её короткие ссылки
        for (WidgetQrLink link : linkRepo.findByWidgetIdAndRevokedAtIsNull(w.getId())) {
            if (!WidgetQrLink.KIND_FEEDBACK.equals(link.getKind())) {
                link.setPromoCodeId(card.getPromoCodeId());
                linkRepo.save(link);
            }
        }
        return card;
    }

    /** Бумага: визитки — только на A4; открытка и листок — на A4 либо на бумаге своего размера. */
    static String paperFor(String format, String paper) {
        if (WidgetCard.FORMAT_CARD.equals(format)) return "A4";
        return format.equals(paper) ? paper : "A4";
    }

    public String wifiPassword(WidgetCard card) {
        return card.getWifiPasswordEnc() == null ? null : encryption.decrypt(card.getWifiPasswordEnc());
    }

    /**
     * Промокод «на повторный визит», созданный прямо со страницы карточки.
     *
     * @param code код; пусто — подбирается сам (SNOVA10, SNOVA10-2…)
     * @throws IllegalArgumentException с текстом для хозяина
     */
    @Transactional
    public PromoCode createPromo(BookingWidget w, String code, boolean percent, BigDecimal value,
                                 LocalDate validUntil, Integer maxUses) {
        if (value == null || value.signum() <= 0 || (percent && value.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException(percent ? "Скидка — от 1 до 100 %" : "Укажите сумму скидки");
        }
        String normalized = PromoCode.normalize(code);
        if (normalized == null) {
            String base = "SNOVA" + value.setScale(0, RoundingMode.HALF_UP).toPlainString();
            normalized = base;
            for (int n = 2; promoRepo.existsByWidgetIdAndCode(w.getId(), normalized); n++) normalized = base + "-" + n;
        } else if (normalized.length() > 40 || !normalized.matches("[A-ZА-ЯЁ0-9_-]+")) {
            throw new IllegalArgumentException("Код — буквы, цифры, дефис и подчёркивание, до 40 символов");
        } else if (promoRepo.existsByWidgetIdAndCode(w.getId(), normalized)) {
            throw new IllegalArgumentException("Промокод «" + normalized + "» уже есть — выберите его в списке");
        }
        PromoCode p = new PromoCode();
        p.setTenantId(w.getTenantId());
        p.setWidgetId(w.getId());
        p.setCode(normalized);
        p.setDiscountType(percent ? PromoCode.TYPE_PERCENT : PromoCode.TYPE_AMOUNT);
        p.setDiscountValue(value.setScale(percent ? 0 : 2, RoundingMode.HALF_UP));
        p.setValidUntil(validUntil);
        p.setMaxUses(maxUses == null || maxUses < 1 ? null : maxUses);
        promoRepo.save(p);

        WidgetCard card = card(w);
        card.setPromoCodeId(p.getId());
        card.setUpdatedAt(LocalDateTime.now());
        cardRepo.save(card);
        for (WidgetQrLink link : linkRepo.findByWidgetIdAndRevokedAtIsNull(w.getId())) {
            if (!WidgetQrLink.KIND_FEEDBACK.equals(link.getKind())) {
                link.setPromoCodeId(p.getId());
                linkRepo.save(link);
            }
        }
        return p;
    }

    // --- Короткие ссылки

    /** Действующая ссылка вида kind; если её ещё нет — создаётся. */
    @Transactional
    public WidgetQrLink link(BookingWidget w, String kind) {
        return linkRepo.findFirstByWidgetIdAndKindAndRevokedAtIsNullOrderByIdDesc(w.getId(), kind)
                .orElseGet(() -> create(w, kind));
    }

    private WidgetQrLink create(BookingWidget w, String kind) {
        WidgetQrLink link = new WidgetQrLink();
        link.setTenantId(w.getTenantId());
        link.setWidgetId(w.getId());
        link.setKind(kind);
        link.setCode(newCode());
        if (!WidgetQrLink.KIND_FEEDBACK.equals(kind)) link.setPromoCodeId(card(w).getPromoCodeId());
        return linkRepo.save(link);
    }

    String newCode() {
        while (true) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            if (!linkRepo.existsByCode(sb.toString())) return sb.toString();
        }
    }

    /**
     * Перевыпускает ссылки на бронирование (карточка и сообщение гостю). Старые остаются
     * в базе отозванными: уже напечатанные карточки открывают страницу бронирования, но
     * промокод больше не подставляют. Ссылку обратной связи перевыпуск не трогает.
     */
    @Transactional
    public void regenerate(BookingWidget w) {
        for (WidgetQrLink link : linkRepo.findByWidgetIdAndRevokedAtIsNull(w.getId())) {
            if (WidgetQrLink.KIND_FEEDBACK.equals(link.getKind())) continue;
            link.setRevokedAt(LocalDateTime.now());
            linkRepo.save(link);
        }
        create(w, WidgetQrLink.KIND_CARD);
        create(w, WidgetQrLink.KIND_MESSAGE);
    }

    public Optional<WidgetQrLink> findLink(String code) {
        if (code == null || code.length() != CODE_LENGTH) return Optional.empty();
        return linkRepo.findByCode(code);
    }

    /**
     * Куда ведёт {@code /q/<code>}: страница бронирования с метками источника и, если
     * ссылка не отозвана, а промокод ещё действует, — с промокодом. Истёкший, выключенный
     * или исчерпанный промокод просто не подставляется: гость увидит обычную цену, без ошибки.
     *
     * @return путь с параметрами, от корня приложения
     */
    public String target(WidgetQrLink link, BookingWidget w, LocalDate today) {
        boolean message = WidgetQrLink.KIND_MESSAGE.equals(link.getKind());
        StringBuilder url = new StringBuilder("/b/").append(w.getSlug()).append('?');
        if (link.getRevokedAt() == null && link.getPromoCodeId() != null) {
            PromoCode promo = promoRepo.findById(link.getPromoCodeId())
                    .filter(p -> p.getWidgetId().equals(w.getId())).orElse(null);
            if (WidgetBookingService.checkPromo(promo, today).error() == null) {
                url.append("promo=").append(java.net.URLEncoder.encode(promo.getCode(), java.nio.charset.StandardCharsets.UTF_8))
                        .append('&');
            }
        }
        url.append("utm_source=").append(message ? SOURCE_MESSAGE : SOURCE_CARD)
                .append("&utm_medium=").append(message ? "messenger" : "print");
        return url.toString();
    }

    // --- Данные для страницы карточки

    /**
     * Всё, что нужно скрипту карточки (widget-card.js) для превью и печати. Отдаётся
     * только хозяину на его страницах: здесь лежит пароль Wi-Fi.
     */
    @Transactional
    public ObjectNode data(BookingWidget w, String baseUrl) {
        WidgetCard card = card(w);
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode data = f.objectNode();
        data.put("widgetTitle", w.getTitle());
        data.put("format", card.getFormat());
        data.put("paper", card.getPaper());
        data.put("lang", card.getLang());
        data.put("mono", Boolean.TRUE.equals(card.getMono()));
        data.put("showContacts", Boolean.TRUE.equals(card.getShowContacts()));
        data.put("showFeedback", Boolean.TRUE.equals(card.getShowFeedback()));
        if (card.getPromoCodeId() == null) data.putNull("promoId");
        else data.put("promoId", card.getPromoCodeId());
        if (card.getPhotoId() == null) data.putNull("photoId");
        else data.put("photoId", card.getPhotoId());

        ObjectNode defaults = data.putObject("defaults");
        DEFAULTS.forEach((lang, map) -> {
            ObjectNode node = defaults.putObject(lang);
            map.forEach(node::put);
        });
        JsonNode saved = card.getTextsJson();
        ObjectNode texts = data.putObject("texts");
        for (String lang : List.of("ru", "en")) {
            ObjectNode node = texts.putObject(lang);
            for (String key : TEXT_KEYS) {
                String value = saved == null ? null : saved.path(lang).path(key).asText(null);
                if (value != null) node.put(key, value);
            }
        }

        LocalDate today = LocalDate.now();
        ArrayNode promos = data.putArray("promos");
        for (PromoCode p : promoRepo.findByWidgetIdAndTenantIdOrderByCreatedAtDesc(w.getId(), w.getTenantId())) {
            boolean valid = WidgetBookingService.checkPromo(p, today).error() == null;
            // Недействующие в список не попадают — кроме выбранного, чтобы хозяин увидел, что с ним не так
            if (!valid && !p.getId().equals(card.getPromoCodeId())) continue;
            ObjectNode node = promos.addObject();
            node.put("id", p.getId());
            node.put("code", p.getCode());
            node.put("ru", discountLabel(p, "ru"));
            node.put("en", discountLabel(p, "en"));
            node.put("valid", valid);
            node.put("until", p.getValidUntil() == null ? null : p.getValidUntil().toString());
        }

        String cardUrl = baseUrl + "/q/" + link(w, WidgetQrLink.KIND_CARD).getCode();
        String feedbackUrl = baseUrl + "/f/" + link(w, WidgetQrLink.KIND_FEEDBACK).getCode();
        ObjectNode links = data.putObject("links");
        links.put("card", cardUrl);
        links.put("message", baseUrl + "/q/" + link(w, WidgetQrLink.KIND_MESSAGE).getCode());
        links.put("feedback", feedbackUrl);
        ObjectNode qr = data.putObject("qr");
        qr.put("card", QrSvg.svg(cardUrl, "QR-код: страница бронирования"));
        qr.put("feedback", QrSvg.svg(feedbackUrl, "QR-код: обратная связь"));

        ObjectNode wifi = data.putObject("wifi");
        String password = wifiPassword(card);
        wifi.put("ssid", card.getWifiSsid());
        wifi.put("password", password);
        wifi.put("qr", card.getWifiSsid() == null ? null
                : QrSvg.svg(QrSvg.wifi(card.getWifiSsid(), password), "QR-код: подключение к Wi-Fi"));

        ArrayNode photoList = data.putArray("photos");
        for (WidgetPhoto p : photos.list(w.getId())) {
            ObjectNode node = photoList.addObject();
            node.put("id", p.getId());
            node.put("url", photos.previewUrl(p, baseUrl));
        }

        // На карточку — только контакты, показ которых хозяин разрешил (согласие на распространение)
        ObjectNode contacts = data.putObject("contacts");
        operators.visibleContacts(w).forEach((k, v) -> contacts.put(k, (String) v));

        JsonNode theme = WidgetLayout.normalize(w.getConfigJson()).path("theme");
        ObjectNode t = data.putObject("theme");
        t.put("accent", theme.path("accent").asText());
        t.put("radius", theme.path("radius").asInt());
        t.put("font", fontFamily(theme.path("font").asText()));
        return data;
    }

    static String defaultText(String lang, String key, PromoCode promo) {
        Map<String, String> d = DEFAULTS.get(lang);
        if (!"subtitle".equals(key)) return d.get(key);
        return promo == null ? d.get("subtitlePlain") : d.get("subtitlePromo").replace("{d}", discountLabel(promo, lang));
    }

    /** «10%» или «500 ₽». */
    static String discountLabel(PromoCode p, String lang) {
        String value = p.getDiscountValue().setScale(0, RoundingMode.HALF_UP).toPlainString();
        return PromoCode.TYPE_PERCENT.equals(p.getDiscountType()) ? value + "%" : value + " ₽";
    }

    public static String fontFamily(String font) {
        String[] f = FONTS.get(font);
        return f == null ? SYSTEM_FONT : "'" + f[0] + "'," + SYSTEM_FONT;
    }

    /** Файлы выбранного шрифта — для preload; системный шрифт файлов не имеет. */
    public static List<String> fontFiles(String font, String baseUrl) {
        if (!FONTS.containsKey(font)) return List.of();
        return List.of(baseUrl + "/fonts/" + font + "-cyrillic-wght-normal.woff2",
                baseUrl + "/fonts/" + font + "-latin-wght-normal.woff2");
    }

    /** @font-face выбранного шрифта: те же файлы и диапазоны, что подключает сам виджет (theme.js). */
    public static String fontCss(String font, String baseUrl) {
        String[] f = FONTS.get(font);
        if (f == null) return "";
        String cyrillic = "U+0301,U+0400-045F,U+0490-0491,U+04B0-04B1,U+2116";
        String latin = "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,"
                + "U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD";
        StringBuilder css = new StringBuilder();
        for (String[] subset : new String[][] {{"cyrillic", cyrillic}, {"latin", latin}}) {
            css.append("@font-face{font-family:'").append(f[0]).append("';font-style:normal;font-display:block;font-weight:")
                    .append(f[1]).append(";src:url(").append(baseUrl).append("/fonts/").append(font).append('-')
                    .append(subset[0]).append("-wght-normal.woff2) format('woff2');unicode-range:").append(subset[1]).append('}');
        }
        return css.toString();
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Настройки карточки из формы. */
    public record CardForm(String format, String paper, String lang, boolean mono, boolean showContacts,
                           boolean showFeedback, Long promoCodeId, Long photoId,
                           String wifiSsid, String wifiPassword, JsonNode texts) {}
}
