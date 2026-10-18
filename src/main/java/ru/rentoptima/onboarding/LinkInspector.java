package ru.rentoptima.onboarding;

import ru.rentoptima.onboarding.PlatformGuides.Platform;

import java.net.URI;
import java.util.Collection;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Что за ссылку вставил хозяин в поле «Ссылка с площадки». Люди путают ссылки между
 * кабинетами, поэтому до скачивания проверяется самое частое:
 * <ul>
 *   <li>вставили НАШУ ссылку (ту, что нужно отдать площадке) — {@link Kind#OWN};</li>
 *   <li>ссылка с другой площадки, чем карточка, — {@link Verdict#mismatch()};</li>
 *   <li>это вообще не ссылка — {@link Kind#INVALID}.</li>
 * </ul>
 * Нераспознанный домен — не ошибка: просто нет подсказки «перенести в другую карточку».
 * Чистая функция, без сети и БД.
 */
public final class LinkInspector {

    public enum Kind { EMPTY, INVALID, OWN, OK }

    /**
     * @param url      ссылка, приведённая к виду с https://
     * @param detected площадка, распознанная по домену; null — не распознали
     * @param mismatch ссылка распознана, но она с другой площадки, чем карточка
     */
    public record Verdict(Kind kind, String url, Platform detected, boolean mismatch) {}

    /** Адрес нашей ссылки для площадки: /ical/<секрет>.ics */
    private static final Pattern OWN_PATH = Pattern.compile("/ical/[A-Za-z0-9_-]+\\.ics/?");

    private LinkInspector() {}

    /**
     * @param raw        что вставил человек — с пробелами, без https://, как есть
     * @param cardPlatform id площадки карточки ({@link PlatformGuides#OTHER} — «Другая»)
     * @param ownHosts   адреса, по которым открывается OptiRent (прод, текущий запрос)
     * @param guides     площадки — по их доменам распознаётся, с какой площадки ссылка
     */
    public static Verdict inspect(String raw, String cardPlatform, Collection<String> ownHosts, PlatformGuides guides) {
        if (raw == null || raw.isBlank()) return new Verdict(Kind.EMPTY, null, null, false);
        String url = raw.trim().replaceAll("\\s+", "");
        // Телефон и мессенджеры при копировании иногда меняют схему календаря на webcal://
        if (url.regionMatches(true, 0, "webcal://", 0, 9)) url = "https://" + url.substring(9);
        if (!url.matches("(?i)^https?://.*")) url = "https://" + url;

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return new Verdict(Kind.INVALID, null, null, false);
        }
        String host = uri.getHost();
        if (host == null) return new Verdict(Kind.INVALID, null, null, false);
        host = host.toLowerCase();

        String path = uri.getPath() == null ? "" : uri.getPath();
        for (String own : ownHosts) {
            if (own == null || own.isBlank()) continue;
            String o = own.toLowerCase();
            if ((host.equals(o) || host.endsWith("." + o) || o.endsWith("." + host)) && OWN_PATH.matcher(path).matches()) {
                return new Verdict(Kind.OWN, url, null, false);
            }
        }
        // Наша ссылка узнаётся и по виду адреса — на случай чужого или тестового домена
        if (host.endsWith("optirent.ru") && path.startsWith("/ical/")) {
            return new Verdict(Kind.OWN, url, null, false);
        }

        // Имя без точки (localhost, имя машины) — не адрес площадки
        if (!host.contains(".")) return new Verdict(Kind.INVALID, null, null, false);

        Optional<Platform> detected = guides.byHost(host);
        boolean mismatch = detected.isPresent() && !detected.get().id().equals(cardPlatform);
        return new Verdict(Kind.OK, url, detected.orElse(null), mismatch);
    }
}
