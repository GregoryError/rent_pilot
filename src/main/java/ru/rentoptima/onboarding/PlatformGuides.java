package ru.rentoptima.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import ru.rentoptima.guide.GuideRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Площадки и инструкции к ним — из базы ({@code guides}, вид PLATFORM, V45; прежде —
 * {@code onboarding/platforms/*.yml}). Один источник для мастера первой настройки, распознавания
 * вставленных ссылок, раздела «Площадки» и страниц {@code /instrukcii/<id>}: шаги правятся в
 * редакторе администратора, код не меняется.
 * <p>
 * Список кешируется; редактор сбрасывает кеш после сохранения ({@link #reload()}). Пока шаги
 * площадки не отмечены проверенными, {@link Platform#fromPlatform()} и {@link Platform#toPlatform()}
 * пустые — мастер и страница показывают общий текст.
 */
@Service
public class PlatformGuides {

    /** «Другая»: площадки нет в списке — общий текст, без распознавания ссылок. */
    public static final String OTHER = "other";

    /** Где взять ссылку или куда её вставить: шаги по кабинету и скриншот. Пусто — общий текст. */
    public record Path(List<String> steps, String screenshot) {
        public static final Path EMPTY = new Path(List.of(), null);

        public boolean isEmpty() {
            return steps.isEmpty();
        }
    }

    public record Platform(String id, int order, String name, String cabinet, String letter, String color,
                           String logo, List<String> domains, boolean domainsDraft, boolean stepsVerified,
                           Path fromPlatform, Path toPlatform, String refresh, List<String> notes) {
        /** Страница инструкции на сайте. */
        public String guidePath() {
            return GuideRepository.PATH_PREFIX + id;
        }
    }

    /** Логотип и скриншоты: файл из {@code static/img/platforms} или изображение, загруженное в редакторе. */
    public static final java.util.regex.Pattern IMAGE = java.util.regex.Pattern.compile(
            "[a-z0-9][a-z0-9._-]*\\.(png|jpe?g|webp|svg)|/media/guides/[0-9a-f]{32}-\\d{2,4}\\.(jpg|webp)");

    /** Адрес логотипа или скриншота для страницы: загруженное — как есть, имя файла — из static/img/platforms. */
    public static String imageUrl(String value) {
        if (value == null) return null;
        return value.startsWith("/") ? value : "/img/platforms/" + value;
    }

    private final GuideRepository repo;
    private volatile List<Platform> cache;

    public PlatformGuides(GuideRepository repo) {
        this.repo = repo;
    }

    /** Для тестов без базы: готовый список. */
    public static PlatformGuides of(List<Platform> platforms) {
        PlatformGuides g = new PlatformGuides(null);
        g.cache = List.copyOf(platforms);
        return g;
    }

    /** Все площадки в порядке плиток мастера. */
    public List<Platform> all() {
        List<Platform> c = cache;
        if (c == null) {
            c = load();
            cache = c;
        }
        return c;
    }

    /** После правки в редакторе — перечитать из базы. */
    public void reload() {
        if (repo != null) cache = load();
    }

    public Optional<Platform> find(String id) {
        return all().stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Площадка по адресу ссылки: домен или его поддомен. Не распознали — пусто, это не ошибка. */
    public Optional<Platform> byHost(String host) {
        if (host == null) return Optional.empty();
        String h = host.toLowerCase();
        return all().stream()
                .filter(p -> p.domains().stream().anyMatch(d -> h.equals(d) || h.endsWith("." + d)))
                .findFirst();
    }

    private List<Platform> load() {
        List<Platform> out = new ArrayList<>();
        for (GuideRepository.Guide g : repo.platforms()) out.add(parse(g.slug(), g.sortOrder(), g.platform()));
        return List.copyOf(out);
    }

    /** Структурные поля площадки из {@code guides.platform}. */
    public static Platform parse(String id, int order, JsonNode p) {
        String name = text(p, "name");
        if (name == null) throw new IllegalStateException("Площадка " + id + ": нет name");
        boolean verified = p.path("stepsVerified").asBoolean(false);
        return new Platform(id, order, name,
                text(p, "cabinet") == null ? name : text(p, "cabinet"),
                text(p, "letter") == null ? name.substring(0, 1).toUpperCase() : text(p, "letter"),
                text(p, "color"), text(p, "logo"), strings(p.path("domains")),
                p.path("domainsDraft").asBoolean(false), verified,
                verified ? path(p.path("fromPlatform")) : Path.EMPTY,
                verified ? path(p.path("toPlatform")) : Path.EMPTY,
                text(p, "refresh") == null ? "10–60 минут" : text(p, "refresh"), strings(p.path("notes")));
    }

    private static Path path(JsonNode node) {
        if (!node.isObject()) return Path.EMPTY;
        String shot = text(node, "screenshot");
        return new Path(strings(node.path("steps")), shot);
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.path(key);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        node.forEach(n -> {
            if (!n.isNull() && !n.asText().isBlank()) out.add(n.asText().trim());
        });
        return List.copyOf(out);
    }
}
