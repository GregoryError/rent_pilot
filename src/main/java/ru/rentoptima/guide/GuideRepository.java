package ru.rentoptima.guide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Инструкции по площадкам и статьи ({@code guides}, V45). Публичный адрес обоих видов —
 * {@code /instrukcii/<slug>}. Черновик виден только администратору; опубликованное со
 * {@code hidden_from_search} открывается, но закрыто от поиска и не входит в sitemap.xml.
 */
@Repository
public class GuideRepository {

    public static final String PLATFORM = "PLATFORM";
    public static final String ARTICLE = "ARTICLE";
    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";

    public static final String PATH_PREFIX = "/instrukcii/";

    public record Guide(long id, String kind, String slug, String title, String description, String body,
                        Long coverImageId, String status, boolean hiddenFromSearch, int sortOrder,
                        JsonNode platform, LocalDateTime publishedAt, LocalDateTime updatedAt) {
        public String path() {
            return PATH_PREFIX + slug;
        }

        public boolean published() {
            return PUBLISHED.equals(status);
        }

        /** Открыта поиску и входит в sitemap.xml. */
        public boolean indexable() {
            return published() && !hiddenFromSearch;
        }

        public boolean isPlatform() {
            return PLATFORM.equals(kind);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final RowMapper<Guide> ROW = (rs, i) -> {
        String platform = rs.getString("platform");
        JsonNode node;
        try {
            node = platform == null ? null : JSON.readTree(platform);
        } catch (Exception e) {
            throw new IllegalStateException("guides.platform не JSON у " + rs.getString("slug"), e);
        }
        return new Guide(rs.getLong("id"), rs.getString("kind"), rs.getString("slug"), rs.getString("title"),
                rs.getString("description"), rs.getString("body_md"), (Long) rs.getObject("cover_image_id"),
                rs.getString("status"), rs.getBoolean("hidden_from_search"), rs.getInt("sort_order"), node,
                rs.getObject("published_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class));
    };

    private static final String SELECT = "SELECT id, kind, slug, title, description, body_md, cover_image_id, status,"
            + " hidden_from_search, sort_order, platform::text AS platform, published_at, updated_at FROM guides";

    private final JdbcTemplate jdbc;

    public GuideRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Все площадки — для мастера, распознавания ссылок и раздела «Площадки», в порядке плиток. */
    public List<Guide> platforms() {
        return jdbc.query(SELECT + " WHERE kind = 'PLATFORM' ORDER BY sort_order, id", ROW);
    }

    /** Все статьи — для списка в админке. */
    public List<Guide> articles() {
        return jdbc.query(SELECT + " WHERE kind = 'ARTICLE' ORDER BY sort_order, id", ROW);
    }

    /** Опубликованные — для списка /instrukcii: сначала площадки, затем статьи, внутри — по порядку. */
    public List<Guide> published() {
        return jdbc.query(SELECT + " WHERE status = 'PUBLISHED' ORDER BY kind DESC, sort_order, id", ROW);
    }

    public Optional<Guide> bySlug(String slug) {
        return jdbc.query(SELECT + " WHERE slug = ?", ROW, slug).stream().findFirst();
    }

    public Optional<Guide> byId(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", ROW, id).stream().findFirst();
    }

    /** Адрес открыт поиску: опубликовано и не скрыто. */
    public boolean indexable(String path) {
        if (path == null || !path.startsWith(PATH_PREFIX)) return false;
        String slug = path.substring(PATH_PREFIX.length());
        if (!slug.matches("[a-z][a-z0-9-]*")) return false;
        Integer n = jdbc.queryForObject("SELECT count(*) FROM guides WHERE slug = ? AND status = 'PUBLISHED'"
                + " AND NOT hidden_from_search", Integer.class, slug);
        return n != null && n > 0;
    }
}
