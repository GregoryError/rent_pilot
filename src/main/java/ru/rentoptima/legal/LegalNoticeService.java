package ru.rentoptima.legal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.mail.Mail;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.seo.SiteSettings;
import ru.rentoptima.service.EmailService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Письмо хозяину страницы бронирования, созданной до V43: заполните сведения об операторе для
 * гостей, иначе через 30 дней форма заявки отключится. Одно на страницу
 * ({@code booking_widgets.legal_notice_sent_at}); в кабинете то же — плашкой.
 */
@Slf4j
@Service
public class LegalNoticeService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"));

    private final BookingWidgetRepository widgetRepo;
    private final GuestOperators operators;
    private final EmailService email;
    private final JdbcTemplate jdbc;
    private final SiteSettings site;

    public LegalNoticeService(BookingWidgetRepository widgetRepo, GuestOperators operators, EmailService email,
                              JdbcTemplate jdbc, SiteSettings site) {
        this.widgetRepo = widgetRepo;
        this.operators = operators;
        this.email = email;
        this.jdbc = jdbc;
        this.site = site;
    }

    /** @return сколько писем отправлено */
    @Scheduled(initialDelay = 600_000, fixedDelay = 6 * 3_600_000)
    public int notifyHosts() {
        if (!email.smtpConfigured()) return 0;
        int sent = 0;
        LocalDateTime now = LocalDateTime.now();
        for (BookingWidget w : widgetRepo.findAll()) {
            if (!Boolean.TRUE.equals(w.getActive()) || w.getLegalGraceUntil() == null
                    || w.getLegalNoticeSentAt() != null || !operators.status(w, now).inGrace()) {
                continue;
            }
            // Отметка — до отправки: лучше одно потерянное письмо, чем письмо каждые шесть часов
            jdbc.update("UPDATE booking_widgets SET legal_notice_sent_at = now() WHERE id = ?", w.getId());
            for (String to : ownerEmails(w.getTenantId())) {
                email.send(mail(to, w, site.getBaseUrl()));
                sent++;
            }
        }
        if (sent > 0) log.info("Письма о сведениях об операторе для гостей: отправлено {}", sent);
        return sent;
    }

    static Mail mail(String to, BookingWidget w, String baseUrl) {
        String until = w.getLegalGraceUntil().toLocalDate().format(DAY);
        return Mail.to(to)
                .subject("Страница бронирования «" + w.getTitle() + "»: заполните сведения для гостей до " + until)
                .heading("Заполните сведения об операторе для гостей")
                .paragraph("Мы опубликовали правовые документы OptiRent. Для гостей, которые оставляют заявку"
                        + " на вашей странице бронирования, вы — оператор их персональных данных: ваше имя"
                        + " и контакт стоят в тексте согласия гостя.")
                .paragraph("Укажите ФИО или наименование и контакт для обращений и дайте согласие на их показ"
                        + " гостям — блок «Что видят гости» в настройках страницы «" + w.getTitle() + "».")
                .paragraph("До " + until + " страница принимает заявки как раньше. Если сведений не будет,"
                        + " с этой даты форма заявки отключится, пока вы их не заполните.")
                .button("Заполнить сведения", baseUrl + "/settings/widgets/" + w.getId() + "#public-data")
                .build();
    }

    private List<String> ownerEmails(Long tenantId) {
        return jdbc.queryForList("SELECT email FROM users WHERE tenant_id = ? AND active AND role = 'OWNER'"
                + " AND email IS NOT NULL", String.class, tenantId);
    }
}
