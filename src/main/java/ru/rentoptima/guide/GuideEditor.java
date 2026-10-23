package ru.rentoptima.guide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.onboarding.PlatformGuides;
import ru.rentoptima.seo.IndexNowService;
import ru.rentoptima.service.ChannelPalette;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Редактирование инструкций и статей. Что в редакторе — рабочая копия ({@code guide_drafts}):
 * автосохранение пишет только её, сайт видит опубликованное ({@code guides}). «Сохранить» —
 * рабочая копия + версия в истории, «Опубликовать» — проверки, перенос на сайт, версия, IndexNow
 * и сброс кеша площадок. «Восстановить» загружает прошлую версию в рабочую копию.
 */
@Service
public class GuideEditor {

    /** Слова, которых хозяин в мастере видеть не должен (как в текстах онбординга). */
    static final List<String> FORBIDDEN = List.of("ical", "импорт", "экспорт", "канал", "фид", "тенант");

    /** Что в редакторе. {@code platform} — структурные поля площадки, у статьи null. */
    public record Form(String title, String slug, String description, String body, Long coverImageId,
                       boolean hiddenFromSearch, int sortOrder, JsonNode platform) {
    }

    public record Version(long id, int version, String kind, LocalDateTime at, String author, Form form) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final GuideRepository guides;
    private final PlatformGuides platformGuides;
    private final IndexNowService indexNow;

    public GuideEditor(JdbcTemplate jdbc, GuideRepository guides, PlatformGuides platformGuides,
                       IndexNowService indexNow) {
        this.jdbc = jdbc;
        this.guides = guides;
        this.platformGuides = platformGuides;
        this.indexNow = indexNow;
    }

    // --- Рабочая копия

    /** Рабочая копия; её ещё нет — опубликованное (или черновик) из guides. */
    public Form draft(GuideRepository.Guide g) {
        List<Form> d = jdbc.query("SELECT title, slug, description, body_md, cover_image_id, hidden_from_search,"
                + " sort_order, platform::text AS platform FROM guide_drafts WHERE guide_id = ?", FORM, g.id());
        return d.isEmpty() ? new Form(g.title(), g.slug(), g.description(), g.body(), g.coverImageId(),
                g.hiddenFromSearch(), g.sortOrder(), g.platform()) : d.get(0);
    }

    /** Статья с полями рабочей копии — для предпросмотра «как на сайте». Статус и даты — как есть. */
    public GuideRepository.Guide withDraft(GuideRepository.Guide g) {
        Form f = draft(g);
        String slug = f.slug() != null && f.slug().matches("[a-z][a-z0-9-]{1,79}") ? f.slug() : g.slug();
        return new GuideRepository.Guide(g.id(), g.kind(), slug, f.title(), f.description(), f.body(),
                f.coverImageId(), g.status(), f.hiddenFromSearch(), f.sortOrder(), f.platform(), g.publishedAt(),
                g.updatedAt());
    }

    /** В рабочей копии есть неопубликованные изменения. */
    public boolean hasUnpublished(GuideRepository.Guide g) {
        return !draft(g).equals(new Form(g.title(), g.slug(), g.description(), g.body(), g.coverImageId(),
                g.hiddenFromSearch(), g.sortOrder(), g.platform()));
    }

    /** Автосохранение: только рабочая копия, без версии и проверок. */
    public void autosave(GuideRepository.Guide g, Form f, Long userId) {
        Form fixed = withFixedSlug(g, f);
        jdbc.update("INSERT INTO guide_drafts(guide_id, title, slug, description, body_md, cover_image_id,"
                        + " hidden_from_search, sort_order, platform, updated_by_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?::jsonb, ?) ON CONFLICT (guide_id) DO UPDATE SET title = EXCLUDED.title, slug = EXCLUDED.slug,"
                        + " description = EXCLUDED.description, body_md = EXCLUDED.body_md,"
                        + " cover_image_id = EXCLUDED.cover_image_id, hidden_from_search = EXCLUDED.hidden_from_search,"
                        + " sort_order = EXCLUDED.sort_order, platform = EXCLUDED.platform,"
                        + " updated_by_user_id = EXCLUDED.updated_by_user_id, updated_at = now()",
                g.id(), cut(fixed.title(), 200), cut(fixed.slug(), 80), cut(fixed.description(), 300),
                fixed.body() == null ? "" : fixed.body(), fixed.coverImageId(), fixed.hiddenFromSearch(),
                fixed.sortOrder(), json(fixed.platform()), userId);
    }

    /** «Сохранить»: рабочая копия + версия. Ошибки формы — те, что не дадут сохранить вовсе. */
    @Transactional
    public List<String> save(GuideRepository.Guide g, Form f, Long userId) {
        List<String> errors = basicErrors(g, f);
        if (!errors.isEmpty()) return errors;
        autosave(g, f, userId);
        addVersion(g.id(), "SAVE", withFixedSlug(g, f), userId);
        return List.of();
    }

    /** «Опубликовать»: все проверки, затем на сайт. Пусто — опубликовано. */
    @Transactional
    public List<String> publish(GuideRepository.Guide g, Form f, Long userId) {
        Form fixed = withFixedSlug(g, f);
        List<String> errors = publishErrors(g, fixed);
        if (!errors.isEmpty()) return errors;
        autosave(g, fixed, userId);
        String oldPath = g.path();
        jdbc.update("UPDATE guides SET title = ?, slug = ?, description = ?, body_md = ?, cover_image_id = ?,"
                        + " hidden_from_search = ?, sort_order = ?, platform = ?::jsonb, status = 'PUBLISHED',"
                        + " published_at = coalesce(published_at, now()), updated_at = now() WHERE id = ?",
                fixed.title().strip(), fixed.slug(), fixed.description().strip(), fixed.body(), fixed.coverImageId(),
                fixed.hiddenFromSearch(), fixed.sortOrder(), json(fixed.platform()), g.id());
        addVersion(g.id(), "PUBLISH", fixed, userId);
        if (g.isPlatform()) platformGuides.reload();
        String newPath = GuideRepository.PATH_PREFIX + fixed.slug();
        // Поисковикам: страница появилась, изменилась или закрылась от поиска; после смены адреса старый — 404
        if (!fixed.hiddenFromSearch() || g.indexable()) indexNow.submit(oldPath.equals(newPath) ? List.of(newPath) : List.of(oldPath, newPath));
        return List.of();
    }

    /** «Снять с публикации»: страница станет 404 для всех, кроме администратора. */
    @Transactional
    public void unpublish(GuideRepository.Guide g) {
        jdbc.update("UPDATE guides SET status = 'DRAFT', updated_at = now() WHERE id = ?", g.id());
        if (g.isPlatform()) platformGuides.reload();
        if (g.indexable()) indexNow.submit(g.path());
    }

    /** Новая статья — черновик с заголовком и адресом. */
    @Transactional
    public long createArticle(String title, String slug, Long userId) {
        Long id = jdbc.queryForObject("INSERT INTO guides(kind, slug, title, status) VALUES ('ARTICLE', ?, ?, 'DRAFT')"
                + " RETURNING id", Long.class, slug, title.strip());
        GuideRepository.Guide g = guides.byId(id).orElseThrow();
        addVersion(id, "SAVE", draft(g), userId);
        return id;
    }

    /** Ошибки создания статьи: заголовок и адрес. */
    public List<String> createErrors(String title, String slug) {
        List<String> errors = new ArrayList<>();
        if (title == null || title.isBlank()) errors.add("Укажите заголовок");
        slugError(slug, null).ifPresent(errors::add);
        return errors;
    }

    // --- История

    public List<Version> versions(long guideId) {
        return jdbc.query("SELECT v.id, v.version, v.kind, v.created_at, u.username, v.title, v.slug, v.description,"
                + " v.body_md, v.cover_image_id, v.hidden_from_search, v.sort_order, v.platform::text AS platform"
                + " FROM guide_versions v LEFT JOIN users u ON u.id = v.created_by_user_id WHERE v.guide_id = ?"
                + " ORDER BY v.version DESC", VERSION, guideId);
    }

    public Optional<Version> version(long guideId, int version) {
        return versions(guideId).stream().filter(v -> v.version() == version).findFirst();
    }

    /** Прошлая версия → рабочая копия (на сайте ничего не меняется, пока её не опубликуют). */
    @Transactional
    public boolean restore(GuideRepository.Guide g, int version, Long userId) {
        Optional<Version> v = version(g.id(), version);
        if (v.isEmpty()) return false;
        autosave(g, v.get().form(), userId);
        addVersion(g.id(), "RESTORE", withFixedSlug(g, v.get().form()), userId);
        return true;
    }

    private void addVersion(long guideId, String kind, Form f, Long userId) {
        jdbc.queryForObject("SELECT id FROM guides WHERE id = ? FOR UPDATE", Long.class, guideId);
        Integer next = jdbc.queryForObject("SELECT coalesce(max(version), 0) + 1 FROM guide_versions WHERE guide_id = ?",
                Integer.class, guideId);
        jdbc.update("INSERT INTO guide_versions(guide_id, version, kind, title, slug, description, body_md,"
                        + " cover_image_id, hidden_from_search, sort_order, platform, created_by_user_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                guideId, next, kind, cut(f.title(), 200), cut(f.slug(), 80), cut(f.description(), 300),
                f.body() == null ? "" : f.body(), f.coverImageId(), f.hiddenFromSearch(), f.sortOrder(),
                json(f.platform()), userId);
    }

    // --- Проверки

    private List<String> basicErrors(GuideRepository.Guide g, Form f) {
        List<String> errors = new ArrayList<>();
        if (f.title() == null || f.title().isBlank()) errors.add("Укажите заголовок");
        if (!g.isPlatform()) slugError(f.slug(), g.id()).ifPresent(errors::add);
        if (g.isPlatform() && (f.platform() == null || text(f.platform(), "name") == null)) {
            errors.add("Укажите название площадки");
        }
        return errors;
    }

    /** Всё, без чего на сайт нельзя. */
    public List<String> publishErrors(GuideRepository.Guide g, Form f) {
        List<String> errors = basicErrors(g, f);
        if (f.description() == null || f.description().isBlank()) {
            errors.add("Добавьте краткое описание — его показывают поисковики");
        }
        List<String> noAlt = GuideMarkdown.imagesWithoutAlt(f.body());
        if (!noAlt.isEmpty()) {
            errors.add("У изображений нет подписи — впишите её в квадратные скобки: ![подпись](адрес). Без подписи: "
                    + String.join(", ", noAlt));
        }
        if (g.isPlatform() && f.platform() != null) errors.addAll(platformErrors(f.platform()));
        return errors;
    }

    private static List<String> platformErrors(JsonNode p) {
        List<String> errors = new ArrayList<>();
        String color = text(p, "color");
        if (color == null || !ChannelPalette.contains(color)) errors.add("Цвет площадки — из палитры шахматки");
        if (!p.path("domains").isArray() || p.path("domains").isEmpty()) {
            errors.add("Нужен хотя бы один домен площадки — по нему узнаются вставленные ссылки");
        }
        for (JsonNode d : p.path("domains")) {
            if (!d.asText().matches("[a-z0-9.-]+\\.[a-z]{2,}")) errors.add("Домен «" + d.asText() + "» — вида cian.ru");
        }
        List<String> shots = new ArrayList<>();
        shots.add(text(p, "logo"));
        shots.add(text(p.path("fromPlatform"), "screenshot"));
        shots.add(text(p.path("toPlatform"), "screenshot"));
        for (String shot : shots) {
            if (shot != null && !PlatformGuides.IMAGE.matcher(shot).matches()) {
                errors.add("Логотип и скриншоты — кнопкой «Загрузить» (или имя файла из img/platforms), не «" + shot + "»");
            }
        }
        StringBuilder shown = new StringBuilder(text(p, "refresh") == null ? "" : text(p, "refresh"));
        for (String key : List.of("notes")) p.path(key).forEach(n -> shown.append(' ').append(n.asText()));
        for (String key : List.of("fromPlatform", "toPlatform")) {
            p.path(key).path("steps").forEach(n -> shown.append(' ').append(n.asText()));
        }
        String lower = shown.toString().toLowerCase(Locale.ROOT);
        for (String word : FORBIDDEN) {
            if (lower.contains(word)) {
                errors.add("В шагах, частоте и особенностях — слова хозяина, без «" + word
                        + "»: «ссылка с площадки», «ссылка для площадки», «площадка»");
            }
        }
        return errors;
    }

    /** Ошибка адреса или пусто. {@code selfId} — сама статья (её текущий адрес не занят). */
    Optional<String> slugError(String slug, Long selfId) {
        if (slug == null || !slug.matches("[a-z][a-z0-9-]{1,79}")) {
            return Optional.of("Адрес — латинские буквы, цифры и дефис, начинается с буквы, от 2 до 80 символов");
        }
        Optional<GuideRepository.Guide> other = guides.bySlug(slug);
        if (other.isPresent() && (selfId == null || other.get().id() != selfId)) {
            return Optional.of("Адрес /instrukcii/" + slug + " уже занят");
        }
        return Optional.empty();
    }

    /** У площадки адрес — её код в мастере и в площадках хозяев: не меняется. */
    private static Form withFixedSlug(GuideRepository.Guide g, Form f) {
        if (!g.isPlatform()) return f;
        return new Form(f.title(), g.slug(), f.description(), f.body(), f.coverImageId(), f.hiddenFromSearch(),
                f.sortOrder(), f.platform());
    }

    // --- Поля площадки из формы

    /** Структурные поля площадки из полей формы; строки списков — по одной в строке. */
    public static ObjectNode platformFrom(String name, String cabinet, String letter, String color, String logo,
                                          String domains, boolean domainsDraft, boolean stepsVerified,
                                          String fromSteps, String fromScreenshot, String toSteps,
                                          String toScreenshot, String refresh, String notes) {
        ObjectNode p = JSON.createObjectNode();
        put(p, "name", name);
        put(p, "cabinet", cabinet);
        put(p, "letter", letter);
        put(p, "color", color);
        put(p, "logo", logo);
        ArrayNode d = p.putArray("domains");
        lines(domains).stream().map(s -> s.toLowerCase(Locale.ROOT)).forEach(d::add);
        p.put("domainsDraft", domainsDraft);
        p.put("stepsVerified", stepsVerified);
        ObjectNode from = p.putObject("fromPlatform");
        lines(fromSteps).forEach(from.putArray("steps")::add);
        put(from, "screenshot", fromScreenshot);
        ObjectNode to = p.putObject("toPlatform");
        lines(toSteps).forEach(to.putArray("steps")::add);
        put(to, "screenshot", toScreenshot);
        put(p, "refresh", refresh);
        lines(notes).forEach(p.putArray("notes")::add);
        return p;
    }

    /** Строки списка поля площадки — для формы. */
    public static String joinLines(JsonNode node) {
        if (node == null || !node.isArray()) return "";
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return String.join("\n", out);
    }

    private static void put(ObjectNode node, String key, String value) {
        if (value == null || value.isBlank()) node.putNull(key);
        else node.put(key, value.strip());
    }

    private static List<String> lines(String text) {
        if (text == null) return List.of();
        return text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.path(key);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText().strip();
    }

    private static String json(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static JsonNode readJson(String s) {
        try {
            return s == null ? null : JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("guides: platform не JSON", e);
        }
    }

    private static final RowMapper<Form> FORM = (rs, i) -> new Form(rs.getString("title"), rs.getString("slug"),
            rs.getString("description"), rs.getString("body_md"), (Long) rs.getObject("cover_image_id"),
            rs.getBoolean("hidden_from_search"), rs.getInt("sort_order"), readJson(rs.getString("platform")));

    private static final RowMapper<Version> VERSION = (rs, i) -> new Version(rs.getLong("id"), rs.getInt("version"),
            rs.getString("kind"), rs.getObject("created_at", LocalDateTime.class), rs.getString("username"),
            FORM.mapRow(rs, i));
}
