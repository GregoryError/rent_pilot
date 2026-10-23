package ru.rentoptima.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.rentoptima.housing.HousingKind;
import ru.rentoptima.onboarding.OnboardingChecklist;
import ru.rentoptima.onboarding.OnboardingFacts;
import ru.rentoptima.onboarding.OnboardingState;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Пользователи для админки (/admin/users): список с поиском, фильтрами и сортировкой и карточка.
 * <p>
 * Только сведения об аккаунте и его настройке: объекты, площадки, страницы бронирования,
 * онбординг, редакции документов, источник регистрации. Данных гостей (имена, контакты, тексты
 * отзывов, брони с именами), паролей и их хешей, токенов, секретов ссылок и адресов фидов здесь
 * нет — запросы их не выбирают. Заявки страниц бронирования — только числом.
 * <p>
 * Аккаунт — тенант: у него может быть несколько пользователей, объекты общие.
 */
@Service
public class AdminUsers {

    public static final int PAGE_SIZE = 50;

    /** Поиск и фильтры списка. Пустая строка и null — без фильтра. */
    public record Query(String q, String status, String verified, String props, String sort, int page) {
        public Query {
            q = q == null ? "" : q.strip();
            status = norm(status, List.of("active", "disabled"));
            verified = norm(verified, List.of("yes", "no"));
            props = norm(props, List.of("yes", "no"));
            sort = sort != null && SORTS.containsKey(sort) ? sort : "created";
            page = Math.max(1, page);
        }

        private static String norm(String v, List<String> allowed) {
            return v != null && allowed.contains(v) ? v : "";
        }
    }

    /** Сортировки: ключ из адреса → ORDER BY. */
    public static final Map<String, String> SORTS = Map.of(
            "created", "u.created_at DESC, u.id DESC",
            "created_asc", "u.created_at ASC, u.id ASC",
            "login", "u.last_login_at DESC NULLS LAST, u.id DESC",
            "email", "lower(coalesce(u.email, u.username)), u.id",
            "props", "props DESC, u.id DESC");

    /** Строка списка: сырые значения, тексты для таблицы собирает контроллер. */
    public record User(long id, long tenantId, String tenantName, String login, String email, String displayName,
                       String role, boolean active, LocalDateTime createdAt, LocalDateTime lastLoginAt,
                       boolean emailVerified, LocalDateTime emailVerifiedAt, LocalDateTime verifyDeadline,
                       int props, List<String> propNames, List<String> kinds, int channels,
                       LocalDateTime lastSyncAt, int pages, int requests30, String branch, String wizardStatus,
                       String wizardStep, LocalDateTime wizardFinishedAt, JsonNode onboardingData,
                       Integer termsVersion, Integer privacyVersion, Integer consentVersion,
                       LocalDateTime termsAt, LocalDateTime consentAt, boolean reconsentRequired,
                       String utmSource, String utmMedium, String utmCampaign, String referrerDomain) {
        public boolean isAdmin() {
            return "ADMIN".equals(role);
        }

        public boolean hasOnboarding() {
            return wizardStatus != null;
        }
    }

    public record Page(List<User> users, int total, int page, int pages) {}

    /** Объект аккаунта — без адреса: он не нужен для поддержки. */
    public record Property(long id, String name, String kind, boolean active, LocalDateTime createdAt, int units) {}

    /** Площадка — без адресов ссылок, секретов и текста ошибки (в нём бывает адрес фида). */
    public record Channel(long id, String name, String type, String unitType, boolean active, LocalDateTime lastSyncAt,
                          int consecutiveErrors, boolean hasError, String platform) {}

    /** Страница бронирования: адрес публичный, заявки — числом. */
    public record BookingPage(long id, String title, String slug, boolean active, boolean indexed, int requests30,
                              int requestsTotal) {}

    /** Другой пользователь того же аккаунта. */
    public record Member(long id, String login, String role, boolean active) {}

    /** Пункт «Первых шагов» — так же, как его считает {@link OnboardingChecklist}, но без записи в воронку. */
    public record Step(String title, boolean done) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SELECT = """
            SELECT u.id, u.tenant_id, t.name AS tenant_name, u.username, u.email, u.display_name, u.role, u.active,
                   u.created_at,
                   u.last_login_at AT TIME ZONE 'Europe/Moscow' AS last_login_at,
                   u.email_verified,
                   u.email_verified_at AT TIME ZONE 'Europe/Moscow' AS email_verified_at,
                   u.verify_deadline AT TIME ZONE 'Europe/Moscow' AS verify_deadline,
                   (SELECT count(*) FROM properties p WHERE p.tenant_id = u.tenant_id AND p.active) AS props,
                   (SELECT string_agg(x.name, E'\\n' ORDER BY x.id) FROM (SELECT p.id, p.name FROM properties p
                     WHERE p.tenant_id = u.tenant_id AND p.active ORDER BY p.id LIMIT 3) x) AS prop_names,
                   (SELECT string_agg(DISTINCT p.kind, ',') FROM properties p
                     WHERE p.tenant_id = u.tenant_id AND p.active) AS kinds,
                   (SELECT count(*) FROM channels c WHERE c.tenant_id = u.tenant_id AND c.active
                     AND c.channel_type = 'ICAL') AS channels,
                   (SELECT max(c.last_sync_at) FROM channels c WHERE c.tenant_id = u.tenant_id AND c.active
                     AND c.channel_type = 'ICAL') AS last_sync_at,
                   (SELECT count(*) FROM booking_widgets w WHERE w.tenant_id = u.tenant_id AND w.active) AS pages,
                   (SELECT count(*) FROM bookings b JOIN channels c ON c.id = b.channel_id
                     WHERE b.tenant_id = u.tenant_id AND c.channel_type = 'WIDGET'
                       AND b.created_at > now() - interval '30 days') AS requests30,
                   o.branch, o.wizard_status, o.wizard_step, o.wizard_finished_at, o.data::text AS onboarding_data,
                   u.terms_version, u.privacy_version, u.consent_version, u.agreed_to_terms_at,
                   u.agreed_to_consent_at, u.legal_reconsent_required,
                   u.signup_utm_source, u.signup_utm_medium, u.signup_utm_campaign, u.signup_referrer_domain
            FROM users u
            JOIN tenants t ON t.id = u.tenant_id
            LEFT JOIN onboarding_state o ON o.user_id = u.id
            """;

    private final JdbcTemplate jdbc;
    private final OnboardingFacts facts;

    public AdminUsers(JdbcTemplate jdbc, OnboardingFacts facts) {
        this.jdbc = jdbc;
        this.facts = facts;
    }

    public Page list(Query query) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (!query.q().isEmpty()) {
            // В нижний регистр — обе стороны в базе: регистр кириллицы зависит от её локали
            String like = "%" + query.q().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (lower(u.username) LIKE lower(?) OR lower(coalesce(u.email, '')) LIKE lower(?)"
                    + " OR lower(coalesce(u.display_name, '')) LIKE lower(?) OR lower(t.name) LIKE lower(?)"
                    + " OR EXISTS (SELECT 1 FROM properties p WHERE p.tenant_id = u.tenant_id"
                    + " AND lower(p.name) LIKE lower(?)))");
            for (int i = 0; i < 5; i++) args.add(like);
        }
        switch (query.status()) {
            case "active" -> where.append(" AND u.active");
            case "disabled" -> where.append(" AND NOT u.active");
            default -> { }
        }
        switch (query.verified()) {
            case "yes" -> where.append(" AND u.email_verified");
            case "no" -> where.append(" AND NOT u.email_verified");
            default -> { }
        }
        String hasProps = "EXISTS (SELECT 1 FROM properties p WHERE p.tenant_id = u.tenant_id AND p.active)";
        switch (query.props()) {
            case "yes" -> where.append(" AND ").append(hasProps);
            case "no" -> where.append(" AND NOT ").append(hasProps);
            default -> { }
        }
        Integer total = jdbc.queryForObject("SELECT count(*) FROM users u JOIN tenants t ON t.id = u.tenant_id"
                + where, Integer.class, args.toArray());
        int t = total == null ? 0 : total;
        int pages = Math.max(1, (t + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(query.page(), pages);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(PAGE_SIZE);
        pageArgs.add((page - 1) * PAGE_SIZE);
        List<User> users = jdbc.query(SELECT + where + " ORDER BY " + SORTS.get(query.sort()) + " LIMIT ? OFFSET ?",
                (rs, i) -> user(rs), pageArgs.toArray());
        return new Page(users, t, page, pages);
    }

    public Optional<User> byId(long id) {
        return jdbc.query(SELECT + " WHERE u.id = ?", (rs, i) -> user(rs), id).stream().findFirst();
    }

    public List<Member> members(long tenantId) {
        return jdbc.query("SELECT id, username, role, active FROM users WHERE tenant_id = ? ORDER BY id",
                (rs, i) -> new Member(rs.getLong("id"), rs.getString("username"), rs.getString("role"),
                        rs.getBoolean("active")), tenantId);
    }

    public List<Property> properties(long tenantId) {
        return jdbc.query("""
                SELECT p.id, p.name, p.kind, p.active, p.created_at,
                       (SELECT coalesce(sum(ut.unit_count), 0) FROM unit_types ut
                         WHERE ut.property_id = p.id AND ut.active) AS units
                FROM properties p WHERE p.tenant_id = ? ORDER BY p.id
                """, (rs, i) -> new Property(rs.getLong("id"), rs.getString("name"), rs.getString("kind"),
                rs.getBoolean("active"), rs.getObject("created_at", LocalDateTime.class), rs.getInt("units")), tenantId);
    }

    public List<Channel> channels(long tenantId) {
        return jdbc.query("""
                SELECT c.id, c.name, c.channel_type, ut.name AS unit_type, c.active, c.last_sync_at,
                       c.consecutive_errors, (c.last_error IS NOT NULL AND c.last_error <> '') AS has_error,
                       c.config_json->>'platform' AS platform
                FROM channels c LEFT JOIN unit_types ut ON ut.id = c.unit_type_id
                WHERE c.tenant_id = ? AND c.channel_type <> 'WIDGET' ORDER BY c.id
                """, (rs, i) -> new Channel(rs.getLong("id"), rs.getString("name"), rs.getString("channel_type"),
                rs.getString("unit_type"), rs.getBoolean("active"), rs.getObject("last_sync_at", LocalDateTime.class),
                rs.getInt("consecutive_errors"), rs.getBoolean("has_error"), rs.getString("platform")), tenantId);
    }

    public List<BookingPage> bookingPages(long tenantId) {
        return jdbc.query("""
                SELECT w.id, w.title, w.slug, w.active, w.search_indexed,
                       (SELECT count(*) FROM bookings b WHERE b.channel_id = w.channel_id
                         AND b.created_at > now() - interval '30 days') AS requests30,
                       (SELECT count(*) FROM bookings b WHERE b.channel_id = w.channel_id) AS requests_total
                FROM booking_widgets w WHERE w.tenant_id = ? ORDER BY w.id
                """, (rs, i) -> new BookingPage(rs.getLong("id"), rs.getString("title"), rs.getString("slug"),
                rs.getBoolean("active"), rs.getBoolean("search_indexed"), rs.getInt("requests30"),
                rs.getInt("requests_total")), tenantId);
    }

    /**
     * «Первые шаги» — те же шесть пунктов, что видит хозяин. Нет онбординга — пусто. Считается
     * здесь, а не через {@link OnboardingChecklist#view}: тот пишет отметки в воронку.
     */
    public List<Step> checklist(User u) {
        if (!u.hasOnboarding()) return List.of();
        OnboardingFacts.Facts f = facts.of(u.tenantId());
        JsonNode d = u.onboardingData();
        return List.of(
                new Step("Жильё добавлено", f.property()),
                new Step("Площадка подключена", f.platform()),
                new Step("Площадка забрала ссылку", f.fetched()),
                new Step("Бронь с площадки увидена", f.booking() && d.path(OnboardingChecklist.SEEN_BOOKING).asBoolean(false)),
                new Step("Страница бронирования создана", f.page()),
                new Step("Карточка напечатана", d.path(OnboardingChecklist.CARD_PRINTED).asBoolean(false)));
    }

    public static String housingLabel(String kind) {
        try {
            return HousingKind.of(kind).getLabel();
        } catch (RuntimeException e) {
            return kind;
        }
    }

    public static String branchLabel(String branch) {
        if (branch == null) return "ветка не выбрана";
        return switch (branch) {
            case OnboardingState.BRANCH_PLATFORMS -> "площадки";
            case OnboardingState.BRANCH_DIRECT -> "прямые брони";
            case OnboardingState.BRANCH_BOTH -> "площадки и прямые брони";
            default -> branch;
        };
    }

    public static String wizardLabel(String status) {
        if (status == null) return "без онбординга";
        return switch (status) {
            case OnboardingState.STATUS_DONE -> "мастер пройден";
            case OnboardingState.STATUS_SKIPPED -> "мастер пропущен";
            default -> "мастер не пройден";
        };
    }

    private static User user(ResultSet rs) throws SQLException {
        String names = rs.getString("prop_names");
        String kinds = rs.getString("kinds");
        String data = rs.getString("onboarding_data");
        JsonNode node;
        try {
            node = data == null ? JSON.createObjectNode() : JSON.readTree(data);
        } catch (Exception e) {
            node = JSON.createObjectNode();
        }
        return new User(rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("tenant_name"),
                rs.getString("username"), rs.getString("email"), rs.getString("display_name"), rs.getString("role"),
                rs.getBoolean("active"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("last_login_at", LocalDateTime.class), rs.getBoolean("email_verified"),
                rs.getObject("email_verified_at", LocalDateTime.class),
                rs.getObject("verify_deadline", LocalDateTime.class), rs.getInt("props"),
                names == null ? List.of() : List.of(names.split("\n")),
                kinds == null ? List.of() : Arrays.stream(kinds.split(",")).sorted().toList(),
                rs.getInt("channels"), rs.getObject("last_sync_at", LocalDateTime.class), rs.getInt("pages"),
                rs.getInt("requests30"), rs.getString("branch"), rs.getString("wizard_status"),
                rs.getString("wizard_step"), rs.getObject("wizard_finished_at", LocalDateTime.class), node,
                (Integer) rs.getObject("terms_version"), (Integer) rs.getObject("privacy_version"),
                (Integer) rs.getObject("consent_version"), rs.getObject("agreed_to_terms_at", LocalDateTime.class),
                rs.getObject("agreed_to_consent_at", LocalDateTime.class), rs.getBoolean("legal_reconsent_required"),
                rs.getString("signup_utm_source"), rs.getString("signup_utm_medium"),
                rs.getString("signup_utm_campaign"), rs.getString("signup_referrer_domain"));
    }
}
