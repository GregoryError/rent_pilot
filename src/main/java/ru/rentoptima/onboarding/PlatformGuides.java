package ru.rentoptima.onboarding;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Площадки и инструкции к ним — из файлов {@code onboarding/platforms/*.yml}. Один источник
 * для мастера первой настройки и для страниц {@code /instrukcii/<id>}: заполнить площадку
 * значит поправить её yml и положить скриншот, код не меняется. Описание полей —
 * {@code onboarding/platforms/README.md}.
 */
public final class PlatformGuides {

    /** «Другая»: площадки нет в списке — общий текст, без распознавания ссылок. */
    public static final String OTHER = "other";

    /** Где взять ссылку или куда её вставить: шаги по кабинету и скриншот. Пусто — общий текст. */
    public record Path(List<String> steps, String screenshot) {
        public boolean isEmpty() {
            return steps.isEmpty();
        }
    }

    public record Platform(String id, int order, String name, String cabinet, String letter, String color,
                           String logo, List<String> domains, boolean domainsDraft, boolean draft,
                           Path fromPlatform, Path toPlatform, String refresh, List<String> notes) {
        /** Страница инструкции на сайте. */
        public String guidePath() {
            return "/instrukcii/" + id;
        }
    }

    public static final List<Platform> ALL = load();

    private PlatformGuides() {}

    public static Optional<Platform> find(String id) {
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Площадка по адресу ссылки: домен из файла или его поддомен. Не распознали — пусто, это не ошибка. */
    public static Optional<Platform> byHost(String host) {
        if (host == null) return Optional.empty();
        String h = host.toLowerCase();
        return ALL.stream()
                .filter(p -> p.domains().stream().anyMatch(d -> h.equals(d) || h.endsWith("." + d)))
                .findFirst();
    }

    private static List<Platform> load() {
        List<Platform> platforms = new ArrayList<>();
        try {
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:onboarding/platforms/*.yml");
            for (Resource file : files) {
                try (InputStream in = file.getInputStream()) {
                    Map<String, Object> y = new Yaml().load(in);
                    platforms.add(parse(y, file.getFilename()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать onboarding/platforms: " + e.getMessage(), e);
        }
        platforms.sort(Comparator.comparingInt(Platform::order));
        return List.copyOf(platforms);
    }

    static Platform parse(Map<String, Object> y, String fileName) {
        String id = text(y, "id");
        if (id == null || !id.matches("[a-z][a-z0-9-]*") || !(id + ".yml").equals(fileName)) {
            throw new IllegalStateException(fileName + ": id должен совпадать с именем файла (латиница, цифры, дефис)");
        }
        String name = text(y, "name");
        if (name == null) throw new IllegalStateException(fileName + ": нет name");
        return new Platform(id, y.get("order") instanceof Number n ? n.intValue() : 100, name,
                text(y, "cabinet") == null ? name : text(y, "cabinet"),
                text(y, "letter") == null ? name.substring(0, 1).toUpperCase() : text(y, "letter"),
                text(y, "color"), text(y, "logo"), strings(y.get("domains")),
                Boolean.TRUE.equals(y.get("domains_draft")), Boolean.TRUE.equals(y.get("draft")),
                path(y.get("from_platform")), path(y.get("to_platform")),
                text(y, "refresh") == null ? "10–60 минут" : text(y, "refresh"), strings(y.get("notes")));
    }

    private static Path path(Object node) {
        if (!(node instanceof Map<?, ?> m)) return new Path(List.of(), null);
        Object shot = m.get("screenshot");
        return new Path(strings(m.get("steps")), shot == null || shot.toString().isBlank() ? null : shot.toString().trim());
    }

    private static String text(Map<String, Object> y, String key) {
        Object v = y.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static List<String> strings(Object node) {
        if (!(node instanceof List<?> list)) return List.of();
        return list.stream().filter(o -> o != null && !o.toString().isBlank()).map(o -> o.toString().trim()).toList();
    }
}
