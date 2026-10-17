package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.entity.Booking;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * В Telegram персональных данных гостя нет (patches/INTEGRATION_LEGAL.md, п. 4): только вид
 * события, название, даты, число гостей и ссылка в кабинет.
 */
@DisplayName("Telegram: сообщения о заявках без данных гостя")
class TelegramNoGuestDataTest {

    static Booking guestBooking() {
        Booking b = new Booking();
        b.setCheckIn(LocalDate.of(2026, 11, 3));
        b.setCheckOut(LocalDate.of(2026, 11, 6));
        b.setNights(3);
        b.setGuestCount(2);
        b.setGuestName("Ж.");
        b.setGuestPhone("+7 900 123-45-67");
        b.setGuestEmail("guest@example.com");
        b.setNotes("Комментарий: паспорт 4010 123456, приедем поздно");
        b.setPublicCode("QX7");
        b.setUtmSource("instagram");
        return b;
    }

    /** Ни одного кусочка данных гостя из тестовой брони. */
    static void assertNoGuestData(String text) {
        assertThat(text).doesNotContain("Ж.", "+7 900", "123-45-67", "guest@example.com", "паспорт", "4010",
                "приедем", "instagram", "Гость:", "гость Ж");
    }

    @Test
    @DisplayName("новая заявка и бронь: название, даты, ночи, гости и ссылка — и ничего о госте")
    void created() {
        Booking b = guestBooking();
        String request = WidgetNotifier.createdText("Лофт у парка", b, LocalDateTime.of(2026, 10, 20, 14, 0),
                "https://optirent.ru");
        String instant = WidgetNotifier.createdText("Лофт у парка", b, null, "https://optirent.ru");

        for (String text : new String[]{request, instant}) {
            assertThat(text).contains("Лофт у парка", "03.11.2026 — 06.11.2026", "ночей: 3", "гостей: 2",
                    "https://optirent.ru/bookings/pending");
            assertNoGuestData(text);
        }
        assertThat(request).contains("новая заявка", "Даты держатся до 20.10 14:00");
        assertThat(instant).contains("новая бронь");
    }

    @Test
    @DisplayName("напоминание об истекающей заявке — без имени и телефона гостя")
    void reminder() {
        String text = WidgetNotifier.reminderText("Лофт у парка", guestBooking(), 90, "https://optirent.ru");

        assertThat(text).contains("скоро истечёт", "Лофт у парка", "03.11.2026 — 06.11.2026", "1 ч 30 мин",
                "https://optirent.ru/bookings/pending");
        assertNoGuestData(text);
    }
}
