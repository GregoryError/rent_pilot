package ru.rentoptima.channel.ical;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ICalParser: реальные фикстуры площадок")
class ICalParserFixtureTest {

    private String load(String path) throws IOException {
        try (var in = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(path),
                "resource not found: " + path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Sutochno: 3 события, одно CANCELLED, даты корректные, DTEND полуоткрытый")
    void sutochno() throws IOException {
        List<ICalEvent> events = ICalParser.parse(load("ical/sutochno-sample.ics"));

        assertThat(events).hasSize(3);

        ICalEvent first = events.get(0);
        assertThat(first.uid()).isEqualTo("sutochno-9182736@sutochno.ru");
        assertThat(first.start()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(first.end()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(first.cancelled()).isFalse();
        assertThat(first.nights()).isEqualTo(4);

        ICalEvent cancelled = events.stream()
                .filter(e -> e.uid().contains("canceled"))
                .findFirst().orElseThrow();
        assertThat(cancelled.cancelled()).isTrue();
    }

    @Test
    @DisplayName("Ostrovok: 2 события, английский SUMMARY, отсутствие STATUS не мешает")
    void ostrovok() throws IOException {
        List<ICalEvent> events = ICalParser.parse(load("ical/ostrovok-sample.ics"));

        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.cancelled()).isFalse();
            assertThat(e.uid()).startsWith("OSTROVOK-BKG-");
        });
        assertThat(events.get(0).summary()).isEqualTo("Not available");
        assertThat(events.get(1).nights()).isEqualTo(3);
    }

    @Test
    @DisplayName("Twil: событие без DTEND трактуется как одна ночь; работает line folding")
    void twil() throws IOException {
        List<ICalEvent> events = ICalParser.parse(load("ical/twil-sample.ics"));

        assertThat(events).hasSize(2);

        ICalEvent oneNight = events.get(0);
        assertThat(oneNight.start()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(oneNight.end()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(oneNight.nights()).isEqualTo(1);

        ICalEvent folded = events.get(1);
        assertThat(folded.summary())
                .contains("dlitel'nyj srok")
                .contains("pereletom");
        assertThat(folded.summary()).contains("gorode,");
    }

    @Test
    @DisplayName("Malformed: битые VEVENT пропущены, валидный извлечён; не роняет парсер")
    void malformed() throws IOException {
        List<ICalEvent> events = ICalParser.parse(load("ical/malformed.ics"));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).uid()).isEqualTo("valid-1@example");
        assertThat(events.get(0).start()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(events.get(0).end()).isEqualTo(LocalDate.of(2026, 12, 3));
    }

    @Test
    @DisplayName("Пустой/null вход — пустой список, не исключение")
    void emptyInput() {
        assertThat(ICalParser.parse(null)).isEmpty();
        assertThat(ICalParser.parse("")).isEmpty();
        assertThat(ICalParser.parse("   \n  \n")).isEmpty();
    }

    @Test
    @DisplayName("CRLF/LF вперемешку: unfold работает на смешанных концах строк")
    void mixedLineEndings() {
        String mixed = "BEGIN:VCALENDAR\r\nVERSION:2.0\nBEGIN:VEVENT\r\n"
                + "UID:mix-1@x\nDTSTART;VALUE=DATE:20261001\r\n"
                + "DTEND;VALUE=DATE:20261003\nSUMMARY:mixed\r\n"
                + "END:VEVENT\nEND:VCALENDAR\r\n";
        List<ICalEvent> events = ICalParser.parse(mixed);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).uid()).isEqualTo("mix-1@x");
    }
}
