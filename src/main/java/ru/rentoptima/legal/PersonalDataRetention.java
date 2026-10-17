package ru.rentoptima.legal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Сроки хранения, обещанные политикой (раздел 5 и п. 8.3):
 * <ul>
 *   <li>заявки и брони со страницы бронирования — через {@link #GUEST_MONTHS} месяцев после
 *       выезда у них стираются имя (инициал), телефон, e-mail и комментарий; даты и число гостей
 *       остаются для статистики хозяина;</li>
 *   <li>ссылки сброса пароля и подтверждения почты ({@code auth_tokens}) удаляются, как только
 *       истекли: 1 час и 7 дней. Проверка раз в час.</li>
 * </ul>
 * Сколько очищено — в журнал приложения (внутреннее положение о защите ПД, «Уничтожение»).
 */
@Slf4j
@Service
public class PersonalDataRetention {

    public static final int GUEST_MONTHS = 12;

    private final JdbcTemplate jdbc;

    public PersonalDataRetention(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return сколько заявок очищено */
    @Scheduled(cron = "0 15 4 * * *")
    public int clearGuestContacts() {
        int cleared = jdbc.update("UPDATE bookings SET guest_name = NULL, guest_phone = NULL, guest_email = NULL,"
                + " notes = NULL, updated_at = now()"
                + " WHERE (source = 'DIRECT_WIDGET' OR data_source = 'WIDGET')"
                + " AND check_out < current_date - make_interval(months => ?)"
                + " AND (guest_name IS NOT NULL OR guest_phone IS NOT NULL OR guest_email IS NOT NULL OR notes IS NOT NULL)",
                GUEST_MONTHS);
        log.info("Срок хранения данных гостей ({} мес. после выезда): очищено заявок — {}", GUEST_MONTHS, cleared);
        return cleared;
    }

    /** @return сколько токенов удалено */
    @Scheduled(cron = "0 5 * * * *")
    public int purgeExpiredTokens() {
        int deleted = jdbc.update("DELETE FROM auth_tokens WHERE expires_at < now()");
        if (deleted > 0) log.info("Удалено истёкших ссылок сброса пароля и подтверждения почты: {}", deleted);
        return deleted;
    }
}
