package ru.rentoptima.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * Служебное письмо: заголовок, абзацы, не больше одной кнопки-ссылки и приписка внизу.
 * Из него {@link MailRenderer} собирает HTML и текстовую часть — сами письма в коде
 * разметку не пишут.
 *
 * @param lang        язык письма ({@code ru} / {@code en}) — для атрибута lang и подписи под кнопкой
 * @param heading     заголовок внутри письма; null — без заголовка
 * @param paragraphs  абзацы; перенос строки внутри абзаца сохраняется
 * @param buttonLabel надпись на кнопке; null — письмо без кнопки
 * @param buttonUrl   адрес кнопки; он же выводится текстом под ней
 * @param footer      приписка мелким шрифтом; null — без неё
 */
public record Mail(String to, String subject, String lang, String heading, List<String> paragraphs,
                   String buttonLabel, String buttonUrl, String footer) {

    public Mail {
        paragraphs = List.copyOf(paragraphs);
        if ((buttonLabel == null) != (buttonUrl == null)) {
            throw new IllegalArgumentException("У кнопки должны быть и надпись, и адрес");
        }
    }

    public boolean english() {
        return "en".equals(lang);
    }

    public static Builder to(String to) {
        return new Builder(to);
    }

    public static final class Builder {
        private final String to;
        private String subject;
        private String lang = "ru";
        private String heading;
        private final List<String> paragraphs = new ArrayList<>();
        private String buttonLabel;
        private String buttonUrl;
        private String footer;

        private Builder(String to) {
            this.to = to;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder lang(String lang) {
            this.lang = lang;
            return this;
        }

        public Builder heading(String heading) {
            this.heading = heading;
            return this;
        }

        public Builder paragraph(String text) {
            if (text != null && !text.isBlank()) paragraphs.add(text.strip());
            return this;
        }

        public Builder button(String label, String url) {
            this.buttonLabel = label;
            this.buttonUrl = url;
            return this;
        }

        public Builder footer(String footer) {
            this.footer = footer;
            return this;
        }

        public Mail build() {
            return new Mail(to, subject, lang, heading, paragraphs, buttonLabel, buttonUrl, footer);
        }
    }
}
