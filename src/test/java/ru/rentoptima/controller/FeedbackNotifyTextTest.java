package ru.rentoptima.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.entity.FeedbackResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Telegram: сообщение об отзыве без текста отзыва и имени гостя")
class FeedbackNotifyTextTest {

    @Test
    @DisplayName("анкета и карточка: вид события, объект и ссылка; имени и текста нет")
    void noReviewData() {
        FeedbackResponse anketa = new FeedbackResponse();
        anketa.setGuestName("Ж.");
        FeedbackResponse card = new FeedbackResponse();
        card.setGuestName("Ж.");
        card.setSource(FeedbackResponse.SOURCE_CARD);
        card.setBookingId(7L);

        String a = FeedbackController.notifyText("Садовая 5", anketa, "https://optirent.ru");
        String c = FeedbackController.notifyText("Садовая 5", card, "https://optirent.ru");

        assertThat(a).contains("новый отзыв", "Садовая 5", "https://optirent.ru/feedback-admin");
        assertThat(c).contains("с карточки", "Садовая 5", "Гость сейчас проживает", "https://optirent.ru/feedback-admin");
        assertThat(a + c).doesNotContain("Ж.", "из 10", "Понравилось", "Что улучшить");
    }
}
