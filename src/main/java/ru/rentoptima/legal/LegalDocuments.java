package ru.rentoptima.legal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Правовые документы и их редакции (V42). Редакция не перезаписывается: правка в админке —
 * новая строка с номером на единицу больше; текущая — с наибольшим номером. На номер
 * ссылаются согласия ({@code users.*_version}, {@code bookings.guest_consent_version},
 * {@code feedback_responses.consent_version}, {@code public_data_consents.document_version}).
 */
@Service
public class LegalDocuments {

    public static final String PRIVACY = "privacy";
    public static final String TERMS = "terms";
    public static final String CONSENT = "consent";
    public static final String CONSENT_PUBLIC = "consent-public";
    public static final String COOKIES = "cookies";
    public static final String COOKIE_CONSENT = "cookie-consent";
    public static final String GUEST_CONSENT = "guest-consent";
    public static final String REVIEW = "review";

    /** Все публикуемые документы — в порядке списка в админке и ссылок между ними. */
    public static final List<String> SLUGS = List.of(
            PRIVACY, TERMS, CONSENT, CONSENT_PUBLIC, COOKIES, COOKIE_CONSENT, GUEST_CONSENT, REVIEW);

    public record Version(String slug, int version, String title, LocalDate effectiveDate, String body,
                          LocalDateTime createdAt) {
        public String path() {
            return "/legal/" + slug;
        }

        public String versionPath() {
            return path() + "/v" + version;
        }

        /** «17 октября 2026 г.» */
        public String effectiveText() {
            return effectiveDate.format(DATE);
        }
    }

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy 'г.'",
            Locale.forLanguageTag("ru"));

    private static final RowMapper<Version> ROW = (rs, i) -> new Version(rs.getString("slug"),
            rs.getInt("version"), rs.getString("title"), rs.getObject("effective_date", LocalDate.class),
            rs.getString("body_md"), rs.getObject("created_at", LocalDateTime.class));

    private static final String SELECT = "SELECT slug, version, title, effective_date, body_md, created_at"
            + " FROM legal_page_versions";

    private final JdbcTemplate jdbc;

    public LegalDocuments(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Version> current(String slug) {
        return jdbc.query(SELECT + " WHERE slug = ? ORDER BY version DESC LIMIT 1", ROW, slug).stream().findFirst();
    }

    public Optional<Version> version(String slug, int version) {
        return jdbc.query(SELECT + " WHERE slug = ? AND version = ?", ROW, slug, version).stream().findFirst();
    }

    /** Все редакции документа, новые первыми. */
    public List<Version> history(String slug) {
        return jdbc.query(SELECT + " WHERE slug = ? ORDER BY version DESC", ROW, slug);
    }

    /** Номер текущей редакции; 0 — документа нет (так не бывает после V42). */
    public int currentVersion(String slug) {
        Integer v = jdbc.queryForObject("SELECT max(version) FROM legal_page_versions WHERE slug = ?",
                Integer.class, slug);
        return v == null ? 0 : v;
    }

    /** Текущие редакции всех документов — для списка в админке. */
    public List<Version> currentAll() {
        List<Version> all = new java.util.ArrayList<>(jdbc.query(SELECT + " v WHERE version ="
                + " (SELECT max(version) FROM legal_page_versions WHERE slug = v.slug)", ROW));
        // Сначала публикуемые документы в порядке SLUGS, затем остальные (если появятся) по адресу
        all.sort(java.util.Comparator.comparingInt((Version v) -> SLUGS.contains(v.slug())
                ? SLUGS.indexOf(v.slug()) : SLUGS.size()).thenComparing(Version::slug));
        return all;
    }

    /** Новая редакция. Прежние остаются как есть. */
    @Transactional
    public Version publish(String slug, String title, LocalDate effectiveDate, String body, Long userId) {
        // Блокировка строки документа: две одновременные правки не получат один номер
        jdbc.queryForObject("SELECT slug FROM legal_pages WHERE slug = ? FOR UPDATE", String.class, slug);
        int next = currentVersion(slug) + 1;
        jdbc.update("INSERT INTO legal_page_versions(slug, version, title, effective_date, body_md, created_by_user_id)"
                + " VALUES (?, ?, ?, ?, ?, ?)", slug, next, title, effectiveDate, body, userId);
        jdbc.update("UPDATE legal_pages SET title = ?, updated_at = now(), updated_by_user_id = ? WHERE slug = ?",
                title, userId, slug);
        return version(slug, next).orElseThrow();
    }
}
