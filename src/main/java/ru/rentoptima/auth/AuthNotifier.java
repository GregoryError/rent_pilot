package ru.rentoptima.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.rentoptima.mail.Mail;
import ru.rentoptima.service.EmailService;
import ru.rentoptima.service.TelegramService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Письма и сообщения о входе: ссылка сброса пароля, «пароль изменён».
 * <p>
 * Ссылки сброса уходят в фоне: ответ на /forgot не должен по времени выдавать, нашёлся ли
 * адрес (с отправкой письма запрос шёл бы заметно дольше).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthNotifier {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d.MM.yyyy HH:mm");

    private final EmailService email;
    private final TelegramService telegram;

    @Async
    public void resetLinkByEmail(String to, String link) {
        email.send(resetMail(to, link));
    }

    @Async
    public void resetLinkByTelegram(Long tenantId, String link) {
        TelegramService.SendResult r = telegram.send(tenantId,
                "OptiRent: ссылка для нового пароля (действует " + PasswordService.RESET_TTL.toMinutes() + " минут):\n"
                        + link + "\n\nЕсли вы не запрашивали смену пароля, ничего не делайте — пароль останется прежним.");
        if (!r.ok()) log.warn("Ссылка сброса пароля не ушла в Telegram tenant {}: {}", tenantId, r.error());
    }

    @Async
    public void passwordChanged(String to, String forgotLink) {
        email.send(changedMail(to, LocalDateTime.now(), forgotLink));
    }

    static Mail resetMail(String to, String link) {
        return Mail.to(to)
                .subject("Новый пароль для OptiRent")
                .heading("Восстановление пароля")
                .paragraph("Мы получили запрос на смену пароля для входа в OptiRent с адресом " + to + ".")
                .button("Задать новый пароль", link)
                .footer("Ссылка действует " + PasswordService.RESET_TTL.toMinutes() + " минут и сработает один раз. "
                        + "Если вы не запрашивали смену пароля, просто не открывайте её — пароль останется прежним.")
                .build();
    }

    static Mail changedMail(String to, LocalDateTime when, String forgotLink) {
        return Mail.to(to)
                .subject("Пароль OptiRent изменён")
                .heading("Пароль изменён")
                .paragraph("Пароль для входа в OptiRent изменён " + when.format(WHEN)
                        + " (по московскому времени). Все остальные входы в аккаунт завершены.")
                .paragraph("Если это были не вы — ответьте на это письмо, мы поможем вернуть доступ. "
                        + "Или сразу задайте новый пароль:")
                .button("Восстановить доступ", forgotLink)
                .build();
    }
}
