package ru.rentoptima.auth;

import java.net.URI;
import java.util.Locale;

/**
 * Откуда пришёл зарегистрировавшийся (V47): метки {@code utm_source}, {@code utm_medium},
 * {@code utm_campaign} и домен сайта, с которого был переход. Только домен — без пути и
 * параметров: в полном адресе бывают чужие идентификаторы. Сохраняется один раз при регистрации,
 * показывается только в админке.
 * <p>
 * Метки доходят до формы так: {@code static/js/signup-source.js} на страницах сайта дописывает их
 * (и домен перехода, параметр {@code ref}) к ссылкам на /register; страница регистрации кладёт их
 * в скрытые поля. Ни cookie, ни хранилища браузера.
 */
public record SignupSource(String utmSource, String utmMedium, String utmCampaign, String referrerDomain) {

    public static final SignupSource NONE = new SignupSource(null, null, null, null);

    static final int MAX_UTM = 100;
    private static final String DOMAIN = "[a-z0-9-]+(\\.[a-z0-9-]+)+";

    /**
     * @param ref     домен перехода из формы; пусто — из {@code referer}
     * @param referer заголовок Referer запроса страницы регистрации
     * @param ownHost свой домен: переход внутри сайта источником не считается
     */
    public static SignupSource of(String utmSource, String utmMedium, String utmCampaign, String ref, String referer,
                                  String ownHost) {
        String domain = domain(ref);
        if (domain == null) domain = domainOfUrl(referer);
        if (domain != null && ownHost != null && sameSite(domain, ownHost)) domain = null;
        return new SignupSource(utm(utmSource), utm(utmMedium), utm(utmCampaign), domain);
    }

    public boolean empty() {
        return utmSource == null && utmMedium == null && utmCampaign == null && referrerDomain == null;
    }

    /** Метка: без управляющих символов, до 100 знаков; пустая — null. */
    static String utm(String v) {
        if (v == null) return null;
        String s = v.replaceAll("\\p{Cntrl}", "").strip();
        if (s.isEmpty()) return null;
        return s.length() > MAX_UTM ? s.substring(0, MAX_UTM) : s;
    }

    /** Домен: строчными, без www., только буквы, цифры, точки и дефисы. */
    static String domain(String v) {
        if (v == null) return null;
        String s = v.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("www.")) s = s.substring(4);
        if (s.length() > 253 || !s.matches(DOMAIN)) return null;
        return s;
    }

    static String domainOfUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            return domain(URI.create(url.strip()).getHost());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean sameSite(String domain, String ownHost) {
        String own = domain(ownHost);
        return own != null && (domain.equals(own) || domain.endsWith("." + own));
    }
}
