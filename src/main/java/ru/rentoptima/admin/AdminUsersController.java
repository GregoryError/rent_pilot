package ru.rentoptima.admin;

import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import ru.rentoptima.seo.SiteSettings;

import java.nio.charset.StandardCharsets;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Раздел «Пользователи»: список (поиск по адресу, имени и названию объекта, фильтры, сортировка,
 * страницы по 50) и карточка пользователя. Только ADMIN (как весь /admin/**). Данных гостей,
 * паролей, токенов и секретов нет — см. {@link AdminUsers}. «Войти как пользователь» нет и не будет.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUsersController {

    private static final Locale RU = Locale.forLanguageTag("ru");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", RU);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", RU);

    private final AdminUsers users;
    private final AdminAudit audit;
    private final AdminUserActions actions;
    private final SiteSettings site;

    /** Строка списка: всё уже текстом, цвета — здесь. */
    public record Row(long id, String login, String displayName, String role, String registered, String lastLogin,
                      String verification, String verificationColor, boolean active, String props, String housing,
                      String channels, String bookingPage, String onboarding, String documents, String source) {}

    /** Пара «подпись — значение» в карточке. */
    public record Field(String label, String value) {}

    @GetMapping("/admin/users")
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) String verified,
                       @RequestParam(required = false) String props,
                       @RequestParam(required = false) String sort,
                       @RequestParam(defaultValue = "1") int page,
                       Model model) {
        AdminUsers.Query query = new AdminUsers.Query(q, status, verified, props, sort, page);
        AdminUsers.Page result = users.list(query);
        model.addAttribute("activePage", "admin-users");
        model.addAttribute("query", query);
        model.addAttribute("rows", result.users().stream().map(this::row).toList());
        model.addAttribute("total", result.total());
        model.addAttribute("page", result.page());
        model.addAttribute("pages", result.pages());
        model.addAttribute("prevUrl", result.page() > 1 ? url(query, result.page() - 1) : null);
        model.addAttribute("nextUrl", result.page() < result.pages() ? url(query, result.page() + 1) : null);
        model.addAttribute("sorts", SORT_LABELS);
        return "pages/admin/users";
    }

    @GetMapping("/admin/users/{id}")
    public String card(@PathVariable long id, Model model) {
        AdminUsers.User u = users.byId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Row row = row(u);
        model.addAttribute("activePage", "admin-users");
        model.addAttribute("u", u);
        model.addAttribute("row", row);
        model.addAttribute("account", List.of(
                new Field("Логин", u.login()),
                new Field("E-mail", u.email() == null ? "—" : u.email()),
                new Field("Отображаемое имя", blank(u.displayName())),
                new Field("Роль", row.role()),
                new Field("Аккаунт", u.tenantName() + " (№ " + u.tenantId() + ")"),
                new Field("Состояние", !u.accountActive() ? "аккаунт отключён"
                        + (u.disabledAt() == null ? "" : " " + dateTime(u.disabledAt()))
                        + (u.publicPagesOff() ? ", страницы бронирования тоже" : ", страницы бронирования работают")
                        : u.active() ? "активен" : "отключён"),
                new Field("Зарегистрирован", dateTime(u.createdAt())),
                new Field("Последний вход", u.lastLoginAt() == null ? "нет данных" : dateTime(u.lastLoginAt())),
                new Field("Почта", row.verification()),
                new Field("Источник регистрации", row.source())));
        model.addAttribute("documents", List.of(
                new Field("Пользовательское соглашение", version(u.termsVersion(), u.termsAt())),
                new Field("Политика конфиденциальности", version(u.privacyVersion(), u.termsAt())),
                new Field("Согласие на обработку ПД", version(u.consentVersion(), u.consentAt())),
                new Field("Повторное согласие", u.reconsentRequired() ? "запрошено, ещё не дано" : "не требуется")));
        model.addAttribute("members", users.members(u.tenantId()));
        model.addAttribute("properties", users.properties(u.tenantId()).stream().map(p -> new Field(
                p.name() + (p.active() ? "" : " (выключен)"),
                AdminUsers.housingLabel(p.kind()) + " · мест: " + p.units() + " · добавлен " + date(p.createdAt())))
                .toList());
        model.addAttribute("channels", users.channels(u.tenantId()).stream().map(c -> new Field(
                c.name() + (c.active() ? "" : " (выключена)"),
                channelType(c.type()) + (c.unitType() == null ? "" : " · " + c.unitType())
                        + " · синхронизация: " + (c.lastSyncAt() == null ? "не было" : dateTime(c.lastSyncAt()))
                        + (c.consecutiveErrors() > 0 ? " · ошибок подряд: " + c.consecutiveErrors()
                        : c.hasError() ? " · была ошибка" : "")
                        + (c.platform() == null ? "" : " · из мастера: " + c.platform())))
                .toList());
        model.addAttribute("bookingPages", users.bookingPages(u.tenantId()));
        model.addAttribute("onboarding", List.of(
                new Field("Мастер", AdminUsers.wizardLabel(u.wizardStatus())
                        + (u.wizardStep() != null && "ACTIVE".equals(u.wizardStatus()) ? " (шаг «" + u.wizardStep() + "»)" : "")
                        + (u.wizardFinishedAt() != null ? ", " + dateTime(u.wizardFinishedAt()) : "")),
                new Field("Ветка", u.hasOnboarding() ? AdminUsers.branchLabel(u.branch()) : "—")));
        model.addAttribute("steps", users.checklist(u));
        model.addAttribute("auditRows", audit.recent(null, "user", u.id(), 100).stream()
                .map(e -> new Field(dateTime(e.at()) + " · " + (e.admin() == null ? "—" : e.admin()),
                        e.label() + " — " + switch (e.result()) {
                            case OK -> "выполнено";
                            case FAILED -> "ошибка";
                            case REFUSED -> "отказано";
                        } + (e.details() == null || e.details().isBlank() ? "" : " · " + e.details())))
                .toList());
        model.addAttribute("hasAdmin", actions.hasAdmin(u.tenantId()));
        return "pages/admin/user";
    }

    // --- Действия: подтверждение — в интерфейсе, запись в журнал — здесь

    @PostMapping("/admin/users/{id}/disable")
    public String disable(@PathVariable long id, @RequestParam(required = false) String publicPages,
                          RedirectAttributes redirect) {
        AdminUsers.User u = user(id);
        boolean publicToo = "true".equals(publicPages);
        AdminUserActions.Outcome r = actions.disable(u.tenantId(), publicToo);
        return done(r, AdminAudit.Action.USER_DISABLE, u, "аккаунт № " + u.tenantId(), redirect);
    }

    @PostMapping("/admin/users/{id}/enable")
    public String enable(@PathVariable long id, RedirectAttributes redirect) {
        AdminUsers.User u = user(id);
        return done(actions.enable(u.tenantId()), AdminAudit.Action.USER_ENABLE, u, "аккаунт № " + u.tenantId(), redirect);
    }

    @PostMapping("/admin/users/{id}/verify-resend")
    public String resendVerification(@PathVariable long id, HttpServletRequest request, RedirectAttributes redirect) {
        AdminUsers.User u = user(id);
        return done(actions.resendVerification(u, site.linkBase(request)), AdminAudit.Action.USER_VERIFY_RESEND, u,
                null, redirect);
    }

    @PostMapping("/admin/users/{id}/reset-link")
    public String resetLink(@PathVariable long id, HttpServletRequest request, RedirectAttributes redirect) {
        AdminUsers.User u = user(id);
        return done(actions.sendResetLink(u, site.linkBase(request)), AdminAudit.Action.USER_RESET_LINK, u, null,
                redirect);
    }

    @PostMapping("/admin/users/{id}/verify")
    public String markVerified(@PathVariable long id, RedirectAttributes redirect) {
        AdminUsers.User u = user(id);
        return done(actions.markVerified(u), AdminAudit.Action.USER_VERIFY_MANUAL, u, null, redirect);
    }

    /** Адреса активных хозяев с подтверждённой почтой — CSV, одна колонка. */
    @PostMapping("/admin/users/emails.csv")
    public ResponseEntity<byte[]> emails() {
        List<String> emails = actions.activeVerifiedEmails();
        StringBuilder csv = new StringBuilder("email\r\n");
        for (String e : emails) csv.append(csvCell(e)).append("\r\n");
        audit.ok(AdminAudit.Action.EMAILS_EXPORT, null, null, "адресов: " + emails.size());
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("optirent-emails-" + java.time.LocalDate.now() + ".csv").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Ячейка CSV: в кавычках, если есть разделитель, кавычка или перевод строки; формулы обезврежены. */
    static String csvCell(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        if (s.contains(",") || s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private AdminUsers.User user(long id) {
        return users.byId(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Журнал и сообщение администратору. В журнал — номера и итог, без адресов и имён. */
    private String done(AdminUserActions.Outcome r, AdminAudit.Action action, AdminUsers.User u, String extra,
                        RedirectAttributes redirect) {
        String details = String.join("; ", java.util.stream.Stream.of(extra, r.details())
                .filter(x -> x != null && !x.isBlank()).toList());
        audit.record(action, "user", u.id(), r.ok() ? AdminAudit.Result.OK : AdminAudit.Result.REFUSED,
                details.isEmpty() ? null : details);
        redirect.addFlashAttribute(r.ok() ? "notice" : "error", r.message());
        return "redirect:/admin/users/" + u.id();
    }

    private static final Map<String, String> SORT_LABELS = new LinkedHashMap<>();

    static {
        SORT_LABELS.put("created", "Сначала новые");
        SORT_LABELS.put("created_asc", "Сначала старые");
        SORT_LABELS.put("login", "По последнему входу");
        SORT_LABELS.put("email", "По адресу");
        SORT_LABELS.put("props", "По числу объектов");
    }

    private Row row(AdminUsers.User u) {
        String verification;
        String color;
        if (u.emailVerified()) {
            verification = "подтверждена" + (u.emailVerifiedAt() == null ? "" : " " + date(u.emailVerifiedAt()));
            color = "var(--green)";
        } else if (u.verifyDeadline() != null && u.verifyDeadline().isBefore(LocalDateTime.now())) {
            verification = "не подтверждена, срок истёк " + date(u.verifyDeadline());
            color = "var(--red)";
        } else {
            verification = "не подтверждена" + (u.verifyDeadline() == null ? "" : ", срок " + date(u.verifyDeadline()));
            color = "var(--amber)";
        }
        String props;
        if (u.props() == 0) {
            props = "нет";
        } else {
            props = u.props() + ": " + String.join(", ", u.propNames())
                    + (u.props() > u.propNames().size() ? " и ещё " + (u.props() - u.propNames().size()) : "");
        }
        String channels = u.channels() == 0 ? "нет"
                : u.channels() + (u.lastSyncAt() == null ? ", синхронизации не было" : ", синхр. " + dateTime(u.lastSyncAt()));
        String page = u.pages() == 0 ? "нет" : "да, заявок за 30 дней: " + u.requests30();
        String onboarding = !u.hasOnboarding() ? "без онбординга"
                : AdminUsers.branchLabel(u.branch()) + " · " + AdminUsers.wizardLabel(u.wizardStatus()) + " · шаги "
                + users.checklist(u).stream().filter(AdminUsers.Step::done).count() + " из 6";
        String documents = u.consentVersion() == null ? "не приняты"
                : "соглашение " + v(u.termsVersion()) + ", политика " + v(u.privacyVersion()) + ", согласие "
                + v(u.consentVersion()) + (u.consentAt() == null ? "" : " — " + date(u.consentAt()));
        return new Row(u.id(), u.login(), blank(u.displayName()), u.isAdmin() ? "администратор" : "хозяин",
                date(u.createdAt()), u.lastLoginAt() == null ? "—" : dateTime(u.lastLoginAt()), verification, color,
                u.active(), props,
                u.kinds().isEmpty() ? "—" : u.kinds().stream().map(AdminUsers::housingLabel).collect(Collectors.joining(", ")),
                channels, page, onboarding, documents, source(u));
    }

    private static String source(AdminUsers.User u) {
        List<String> parts = new ArrayList<>();
        if (u.utmSource() != null) parts.add("источник " + u.utmSource());
        if (u.utmMedium() != null) parts.add("канал " + u.utmMedium());
        if (u.utmCampaign() != null) parts.add("кампания " + u.utmCampaign());
        if (u.referrerDomain() != null) parts.add("переход с " + u.referrerDomain());
        return parts.isEmpty() ? "—" : String.join(", ", parts);
    }

    private static String version(Integer v, LocalDateTime at) {
        if (v == null) return "не принят";
        return "редакция " + v + (at == null ? "" : ", " + dateTime(at));
    }

    private static String v(Integer version) {
        return version == null ? "—" : "ред. " + version;
    }

    private static String channelType(String type) {
        return switch (type) {
            case "ICAL" -> "площадка по ссылке";
            case "MANUAL" -> "ручные записи";
            case "AVITO" -> "Авито";
            default -> type;
        };
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    static String date(LocalDateTime t) {
        return t == null ? "—" : t.format(DATE);
    }

    static String dateTime(LocalDateTime t) {
        return t == null ? "—" : t.format(DATE_TIME);
    }

    private static String url(AdminUsers.Query q, int page) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/admin/users");
        if (!q.q().isEmpty()) b.queryParam("q", q.q());
        if (!q.status().isEmpty()) b.queryParam("status", q.status());
        if (!q.verified().isEmpty()) b.queryParam("verified", q.verified());
        if (!q.props().isEmpty()) b.queryParam("props", q.props());
        if (!"created".equals(q.sort())) b.queryParam("sort", q.sort());
        b.queryParam("page", page);
        return b.encode().build().toUriString();
    }
}
