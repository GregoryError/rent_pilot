package ru.rentoptima.legal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.User;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Согласия пользователя кабинета: соглашение и политика (первый чекбокс, вместе с «мне больше
 * 18 лет»), согласие на обработку ПД (второй). Сохраняются время и номера редакций. При
 * регистрации — {@link #apply}; у тех, кто был до V42, — окно согласий при входе
 * ({@code LegalConsentInterceptor}), {@link #accept}.
 */
@Service
public class AccountConsents {

    private final JdbcTemplate jdbc;
    private final LegalDocuments documents;

    public AccountConsents(JdbcTemplate jdbc, LegalDocuments documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    /** Новый пользователь: заполняет поля до сохранения. */
    public void apply(User user, LocalDateTime now) {
        user.setAgreedToTermsAt(now);
        user.setAgreedToConsentAt(now);
        user.setTermsVersion(documents.currentVersion(LegalDocuments.TERMS));
        user.setPrivacyVersion(documents.currentVersion(LegalDocuments.PRIVACY));
        user.setConsentVersion(documents.currentVersion(LegalDocuments.CONSENT));
        user.setLegalReconsentRequired(false);
    }

    /** Кабинет закрыт окном согласий, пока пользователь их не даст. */
    public boolean required(Long userId) {
        List<Boolean> r = jdbc.queryForList("SELECT legal_reconsent_required FROM users WHERE id = ?",
                Boolean.class, userId);
        return !r.isEmpty() && Boolean.TRUE.equals(r.get(0));
    }

    /** Пользователь отметил оба чекбокса в окне согласий. */
    public void accept(Long userId) {
        jdbc.update("UPDATE users SET agreed_to_terms_at = now(), agreed_to_consent_at = now(),"
                        + " terms_version = ?, privacy_version = ?, consent_version = ?,"
                        + " legal_reconsent_required = FALSE WHERE id = ?",
                documents.currentVersion(LegalDocuments.TERMS), documents.currentVersion(LegalDocuments.PRIVACY),
                documents.currentVersion(LegalDocuments.CONSENT), userId);
    }
}
