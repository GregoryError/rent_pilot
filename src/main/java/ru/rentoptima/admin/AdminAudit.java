package ru.rentoptima.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.rentoptima.security.AuthContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Журнал действий администратора ({@code admin_audit_log}, V44). Каждое действие раздела
 * администрирования, которое что-то меняет или выгружает, пишет строку: кто, когда, что, над
 * чем, результат. В {@code details} — только служебное (адрес документа, режим, число строк),
 * без e-mail, имён и других персональных данных.
 */
@Service
public class AdminAudit {

    /** Действия и их подписи на странице журнала. */
    public enum Action {
        LEGAL_PUBLISH("Новая редакция документа"),
        REGISTRATION_MODE("Режим регистрации"),
        USER_DISABLE("Аккаунт отключён"),
        USER_ENABLE("Аккаунт включён"),
        USER_VERIFY_RESEND("Письмо подтверждения отправлено повторно"),
        USER_RESET_LINK("Ссылка сброса пароля отправлена"),
        USER_VERIFY_MANUAL("Почта отмечена подтверждённой"),
        USER_EXPORT("Выгрузка данных пользователя"),
        USER_DELETE("Аккаунт удалён по запросу"),
        EMAILS_EXPORT("Выгрузка e-mail пользователей"),
        GUIDE_PUBLISH("Инструкция или статья опубликована"),
        GUIDE_UNPUBLISH("Инструкция или статья снята с публикации"),
        GUIDE_SAVE("Инструкция или статья сохранена"),
        GUIDE_RESTORE("Восстановлена прежняя версия"),
        GUIDE_CREATE("Создана статья");

        public final String label;

        Action(String label) {
            this.label = label;
        }
    }

    public enum Result { OK, FAILED, REFUSED }

    public record Entry(long id, LocalDateTime at, String admin, Action action, String targetType, Long targetId,
                        Result result, String details) {
        public String label() {
            return action.label;
        }
    }

    private final JdbcTemplate jdbc;

    public AdminAudit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Action action, String targetType, Long targetId, Result result, String details) {
        jdbc.update("INSERT INTO admin_audit_log(admin_user_id, action, target_type, target_id, result, details)"
                        + " VALUES (?, ?, ?, ?, ?, ?)", AuthContext.userId(), action.name(), targetType, targetId,
                result.name(), details == null ? null : details.length() > 500 ? details.substring(0, 500) : details);
    }

    public void ok(Action action, String targetType, Long targetId, String details) {
        record(action, targetType, targetId, Result.OK, details);
    }

    /** Последние записи, новые первыми; фильтры необязательны. */
    public List<Entry> recent(Action action, String targetType, Long targetId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT l.id, l.created_at, u.username AS admin, l.action, l.target_type,"
                + " l.target_id, l.result, l.details FROM admin_audit_log l LEFT JOIN users u ON u.id = l.admin_user_id"
                + " WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (action != null) {
            sql.append(" AND l.action = ?");
            args.add(action.name());
        }
        if (targetType != null && targetId != null) {
            sql.append(" AND l.target_type = ? AND l.target_id = ?");
            args.add(targetType);
            args.add(targetId);
        }
        sql.append(" ORDER BY l.id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 1000)));
        return jdbc.query(sql.toString(), (rs, i) -> new Entry(rs.getLong("id"),
                rs.getObject("created_at", LocalDateTime.class), rs.getString("admin"),
                Action.valueOf(rs.getString("action")), rs.getString("target_type"),
                (Long) rs.getObject("target_id"), Result.valueOf(rs.getString("result")), rs.getString("details")),
                args.toArray());
    }

    public static Map<String, String> actionLabels() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (Action a : Action.values()) m.put(a.name(), a.label);
        return m;
    }
}
