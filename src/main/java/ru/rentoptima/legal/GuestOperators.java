package ru.rentoptima.legal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.BookingWidget;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Хозяин — оператор данных своих гостей (patches/INTEGRATION_LEGAL.md, п. 9–10):
 * <ul>
 *   <li>сведения об операторе для гостей ({@code guest_operators}, одни на аккаунт) стоят
 *       в согласии гостя /legal/guest-consent/&lt;slug&gt;;</li>
 *   <li>показ гостям контактов и этих сведений — распространение: только с согласием
 *       ({@code public_data_consents}, строка на каждое изменение набора полей). Показывается
 *       поле, которое заполнено и есть в последнем согласии страницы;</li>
 *   <li>страница принимает заявки, только когда имя и контакт оператора заполнены и показ их
 *       разрешён. Страницам до V43 — {@code legal_grace_until} с запасным текстом.</li>
 * </ul>
 */
@Service
public class GuestOperators {

    /** Поля, которые видят гости. Ключ пишется в журнал согласий. */
    public enum Field {
        PHONE("phone", "телефон"),
        TELEGRAM("telegram", "Telegram"),
        WHATSAPP("whatsapp", "WhatsApp"),
        OPERATOR_NAME("operator_name", "ФИО или наименование оператора"),
        OPERATOR_CONTACT("operator_contact", "контакт для обращений по персональным данным"),
        OPERATOR_INN("operator_inn", "ИНН"),
        OPERATOR_ADDRESS("operator_address", "адрес");

        public final String key;
        public final String label;

        Field(String key, String label) {
            this.key = key;
            this.label = label;
        }

        static Optional<Field> of(String key) {
            return Arrays.stream(values()).filter(f -> f.key.equals(key)).findFirst();
        }
    }

    public record Info(String name, String contact, String inn, String address) {
        boolean complete() {
            return name != null && contact != null;
        }
    }

    /**
     * Состояние страницы бронирования.
     *
     * @param current     что сейчас заполнено и включено к показу
     * @param consented   что разрешено последним согласием
     * @param ready       имя и контакт оператора заполнены и разрешены к показу
     * @param accepting   форма заявки работает: {@code ready} или ещё идёт срок страницы до V43
     * @param graceUntil  срок для страницы до V43; null — срока нет
     */
    public record Status(Set<Field> current, Set<Field> consented, Info info, boolean ready, boolean accepting,
                         LocalDateTime graceUntil) {
        /** Набор полей изменился — согласие нужно дать заново. */
        public boolean needsConsent() {
            return !current.isEmpty() && !current.equals(consented);
        }

        /** Сейчас идёт срок с запасным текстом: сведений нет, но заявки принимаются. */
        public boolean inGrace() {
            return !ready && accepting;
        }

        /** Что видят гости: заполнено, включено и разрешено. */
        public Set<Field> shown() {
            Set<Field> s = EnumSet.noneOf(Field.class);
            s.addAll(current);
            s.retainAll(consented);
            return s;
        }

        public String currentLabels() {
            return labels(current);
        }

        public String shownLabels() {
            return labels(shown());
        }
    }

    private final JdbcTemplate jdbc;

    public GuestOperators(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- Сведения об операторе

    public Optional<Info> info(Long tenantId) {
        return jdbc.query("SELECT name, contact, inn, address FROM guest_operators WHERE tenant_id = ?",
                (rs, i) -> new Info(rs.getString("name"), rs.getString("contact"), rs.getString("inn"),
                        rs.getString("address")), tenantId).stream().findFirst();
    }

    /** Имя и контакт обязательны: без них сведения не сохраняются (возвращает false). */
    public boolean saveInfo(Long tenantId, Info info) {
        if (info.name() == null || info.contact() == null) return false;
        jdbc.update("INSERT INTO guest_operators(tenant_id, name, contact, inn, address) VALUES (?, ?, ?, ?, ?)"
                + " ON CONFLICT (tenant_id) DO UPDATE SET name = EXCLUDED.name, contact = EXCLUDED.contact,"
                + " inn = EXCLUDED.inn, address = EXCLUDED.address, updated_at = now()",
                tenantId, info.name(), info.contact(), info.inn(), info.address());
        return true;
    }

    // --- Согласие на распространение

    /**
     * Поля, которые увидят гости: заполненные контакты (до брони — если не скрыт блок «Контакты»,
     * это решает {@code WidgetConfigService}; после брони — гостю, отправившему заявку) и сведения
     * об операторе (в согласии гостя).
     */
    public Set<Field> currentFields(BookingWidget w, Optional<Info> info) {
        Set<Field> s = EnumSet.noneOf(Field.class);
        if (filled(w.getContactPhone())) s.add(Field.PHONE);
        if (filled(w.getContactTelegram())) s.add(Field.TELEGRAM);
        if (filled(w.getContactWhatsapp())) s.add(Field.WHATSAPP);
        info.ifPresent(i -> {
            if (filled(i.name())) s.add(Field.OPERATOR_NAME);
            if (filled(i.contact())) s.add(Field.OPERATOR_CONTACT);
            if (filled(i.inn())) s.add(Field.OPERATOR_INN);
            if (filled(i.address())) s.add(Field.OPERATOR_ADDRESS);
        });
        return s;
    }

    /** Поля последнего согласия страницы; пусто — согласия не было. */
    public Set<Field> consentedFields(Long widgetId) {
        List<String> rows = jdbc.queryForList("SELECT fields FROM public_data_consents WHERE widget_id = ?"
                + " ORDER BY id DESC LIMIT 1", String.class, widgetId);
        Set<Field> s = EnumSet.noneOf(Field.class);
        if (rows.isEmpty() || rows.get(0).isBlank()) return s;
        for (String key : rows.get(0).split(",")) Field.of(key.strip()).ifPresent(s::add);
        return s;
    }

    /** Новая запись журнала: текущий набор полей страницы, версия документа, кто и когда. */
    @Transactional
    public Set<Field> recordConsent(BookingWidget w, Long userId, String userEmail, int documentVersion) {
        Set<Field> fields = currentFields(w, info(w.getTenantId()));
        String keys = fields.stream().map(f -> f.key).collect(Collectors.joining(","));
        jdbc.update("INSERT INTO public_data_consents(tenant_id, widget_id, user_id, user_email, fields,"
                + " document_version) VALUES (?, ?, ?, ?, ?, ?)",
                w.getTenantId(), w.getId(), userId, userEmail, keys, documentVersion);
        return fields;
    }

    public Status status(BookingWidget w) {
        return status(w, LocalDateTime.now());
    }

    public Status status(BookingWidget w, LocalDateTime now) {
        Optional<Info> info = info(w.getTenantId());
        Set<Field> current = currentFields(w, info);
        Set<Field> consented = consentedFields(w.getId());
        boolean ready = info.map(Info::complete).orElse(false)
                && consented.contains(Field.OPERATOR_NAME) && consented.contains(Field.OPERATOR_CONTACT);
        boolean grace = w.getLegalGraceUntil() != null && now.isBefore(w.getLegalGraceUntil());
        return new Status(current, consented, info.orElse(null), ready, ready || grace, w.getLegalGraceUntil());
    }

    /** Контакты для гостей: только поля, показ которых разрешён. Ключи — как в API виджета. */
    public Map<String, Object> visibleContacts(BookingWidget w) {
        Set<Field> shown = status(w).shown();
        Map<String, Object> contacts = new LinkedHashMap<>();
        contacts.put("phone", shown.contains(Field.PHONE) ? w.getContactPhone() : null);
        contacts.put("telegram", shown.contains(Field.TELEGRAM) ? w.getContactTelegram() : null);
        contacts.put("whatsapp", shown.contains(Field.WHATSAPP) ? w.getContactWhatsapp() : null);
        return contacts;
    }

    // --- Имя хоста для гостя

    /**
     * Как назвать хоста гостю (согласие в форме заявки, анкета отзыва): имя оператора, если его
     * показ разрешён на какой-нибудь странице хозяина, иначе отображаемое имя аккаунта; пусто —
     * просто «хост».
     */
    public String hostName(Long tenantId) {
        Optional<Info> info = info(tenantId);
        if (info.isPresent()) {
            Integer allowed = jdbc.queryForObject("SELECT count(*) FROM booking_widgets w WHERE w.tenant_id = ?"
                    + " AND (SELECT c.fields FROM public_data_consents c WHERE c.widget_id = w.id"
                    + " ORDER BY c.id DESC LIMIT 1) LIKE ?", Integer.class, tenantId,
                    "%" + Field.OPERATOR_NAME.key + "%");
            if (allowed != null && allowed > 0) return info.get().name();
        }
        return ownerDisplayName(tenantId);
    }

    /**
     * Имя хоста у формы заявки этой страницы: имя оператора, если сведения заполнены и показ
     * разрешён; иначе (страница до V43 в свой срок) — отображаемое имя аккаунта.
     */
    public String widgetHostName(BookingWidget w) {
        Status st = status(w);
        return st.ready() ? st.info().name() : ownerDisplayName(w.getTenantId());
    }

    String ownerDisplayName(Long tenantId) {
        List<String> names = jdbc.queryForList("SELECT display_name FROM users WHERE tenant_id = ? AND active"
                + " AND role = 'OWNER' ORDER BY id LIMIT 1", String.class, tenantId);
        return names.isEmpty() || names.get(0) == null ? "" : names.get(0).strip();
    }

    /**
     * Блок «Кому» для согласия гостя в Markdown: данные хозяина подставляются через
     * {@link LegalMarkdown#literal} — как текст, без разметки и ссылок.
     */
    public String operatorMarkdown(BookingWidget w) {
        Status st = status(w);
        if (st.ready()) {
            Info i = st.info();
            Set<Field> shown = st.shown();
            StringBuilder sb = new StringBuilder("**Оператор** — хост: ").append(LegalMarkdown.literal(i.name()));
            if (shown.contains(Field.OPERATOR_INN)) sb.append(", ИНН ").append(LegalMarkdown.literal(i.inn()));
            sb.append(".\n\nКонтакт для обращений по персональным данным: ")
                    .append(LegalMarkdown.literal(i.contact())).append(".");
            if (shown.contains(Field.OPERATOR_ADDRESS)) {
                sb.append("\n\nАдрес: ").append(LegalMarkdown.literal(i.address())).append(".");
            }
            return sb.toString();
        }
        if (st.accepting()) return fallbackMarkdown(ownerDisplayName(w.getTenantId()));
        return "**Оператор** — хост этой страницы бронирования. Он ещё не указал сведения о себе как об операторе,"
                + " поэтому заявки на странице сейчас не принимаются.";
    }

    /** Запасной текст для страниц до V43, пока хозяин не заполнил сведения. */
    static String fallbackMarkdown(String displayName) {
        String host = displayName == null || displayName.isBlank() ? "" : " " + LegalMarkdown.literal(displayName);
        return "**Оператор** — хост" + host + ", у которого вы бронируете жильё.\n\n"
                + "Контакт для обращений по персональным данным: " + Operator.EMAIL + " — сервис OptiRent"
                + " перешлёт ваш запрос хосту в течение 3 рабочих дней.";
    }

    /** Блок «Кому» общего текста /legal/guest-consent (без страницы хоста). */
    public static String genericOperatorMarkdown() {
        return "**Оператор** — хост, у которого вы бронируете жильё. Его сведения указаны в согласии,"
                + " ссылка на которое стоит у чекбокса в форме заявки.";
    }

    /** Страницы хозяина, которые без сведений об операторе перестанут принимать заявки. */
    public List<BookingWidget> pendingInGrace(List<BookingWidget> widgets) {
        List<BookingWidget> pending = new ArrayList<>();
        for (BookingWidget w : widgets) {
            Status st = status(w);
            if (st.inGrace()) pending.add(w);
        }
        return pending;
    }

    static String labels(Set<Field> fields) {
        return fields.stream().map(f -> f.label).collect(Collectors.joining(", "));
    }

    private static boolean filled(String s) {
        return s != null && !s.isBlank();
    }
}
