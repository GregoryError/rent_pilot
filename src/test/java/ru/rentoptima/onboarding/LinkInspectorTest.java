package ru.rentoptima.onboarding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.onboarding.LinkInspector.Kind;
import ru.rentoptima.onboarding.LinkInspector.Verdict;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Мастер: что за ссылку вставил хозяин и что в ней нашлось")
class LinkInspectorTest {

    private static final List<String> OWN = List.of("optirent.ru", "staging.optirent.ru");
    // Вид настоящих ссылок площадок; номера и ключи вымышленные
    private static final String CIAN = "https://www.cian.ru/calendar/ical/calendars/100200300_11111111-2222-3333-4444-555555555555.ics";
    private static final String DOMCLICK = "https://24.domclick.ru/calendar/export/abcdef.ics";

    @Test
    @DisplayName("ссылка той же площадки, что и карточка, — без замечаний")
    void sameplatform() {
        Verdict v = LinkInspector.inspect(CIAN, "cian", OWN);
        assertThat(v.kind()).isEqualTo(Kind.OK);
        assertThat(v.detected().id()).isEqualTo("cian");
        assertThat(v.mismatch()).isFalse();
        assertThat(LinkInspector.inspect(DOMCLICK, "domclick", OWN).mismatch()).isFalse();
    }

    @Test
    @DisplayName("ссылка Циана в карточке Авито — предложим перенести в карточку Циан")
    void otherPlatform() {
        Verdict v = LinkInspector.inspect(CIAN, "avito", OWN);
        assertThat(v.kind()).isEqualTo(Kind.OK);
        assertThat(v.mismatch()).isTrue();
        assertThat(v.detected().name()).isEqualTo("Циан");

        Verdict d = LinkInspector.inspect(DOMCLICK, "cian", OWN);
        assertThat(d.mismatch()).isTrue();
        assertThat(d.detected().id()).isEqualTo("domclick");
    }

    @Test
    @DisplayName("наша ссылка в поле «Ссылка с площадки» — узнаётся на prod, staging и по адресу запроса")
    void ownLink() {
        assertThat(LinkInspector.inspect("https://optirent.ru/ical/AbC-123_xyz.ics", "cian", OWN).kind()).isEqualTo(Kind.OWN);
        assertThat(LinkInspector.inspect("https://staging.optirent.ru/ical/AbC123.ics", "avito", OWN).kind()).isEqualTo(Kind.OWN);
        assertThat(LinkInspector.inspect("http://localhost:8080/ical/AbC123.ics", "avito", List.of("localhost")).kind())
                .isEqualTo(Kind.OWN);
        assertThat(LinkInspector.inspect("https://my.host.ru/ical/AbC123.ics", "avito", List.of("my.host.ru")).kind())
                .isEqualTo(Kind.OWN);
        // чужой календарь с похожим путём нашим не считается
        assertThat(LinkInspector.inspect("https://example.com/ical/AbC123.ics", "avito", OWN).kind()).isEqualTo(Kind.OK);
    }

    @Test
    @DisplayName("нераспознанный домен — не ошибка: просто нет подсказки о переносе")
    void unknownHost() {
        Verdict v = LinkInspector.inspect("https://calendar.example.org/feed/123.ics", "avito", OWN);
        assertThat(v.kind()).isEqualTo(Kind.OK);
        assertThat(v.detected()).isNull();
        assertThat(v.mismatch()).isFalse();
        // в карточке «Другая» распознанная площадка — повод предложить её карточку
        assertThat(LinkInspector.inspect(CIAN, PlatformGuides.OTHER, OWN).mismatch()).isTrue();
    }

    @Test
    @DisplayName("ссылка как её копируют: с пробелами, без https://, с webcal://")
    void messyInput() {
        assertThat(LinkInspector.inspect("  www.cian.ru/calendar/ical/calendars/1.ics \n", "cian", OWN).url())
                .isEqualTo("https://www.cian.ru/calendar/ical/calendars/1.ics");
        assertThat(LinkInspector.inspect("webcal://www.cian.ru/calendar/ical/calendars/1.ics", "cian", OWN).url())
                .startsWith("https://www.cian.ru/");
        assertThat(LinkInspector.inspect("", "cian", OWN).kind()).isEqualTo(Kind.EMPTY);
        assertThat(LinkInspector.inspect(null, "cian", OWN).kind()).isEqualTo(Kind.EMPTY);
        assertThat(LinkInspector.inspect("просто текст", "cian", OWN).kind()).isEqualTo(Kind.INVALID);
        assertThat(LinkInspector.inspect("https://", "cian", OWN).kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    @DisplayName("разбор календаря: считаются только будущие неотменённые периоды")
    void feedResult() {
        LocalDate today = LocalDate.of(2026, 10, 13);
        String ics = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:past
                DTSTART;VALUE=DATE:20260901
                DTEND;VALUE=DATE:20260905
                END:VEVENT
                BEGIN:VEVENT
                UID:a
                DTSTART;VALUE=DATE:20261020
                DTEND;VALUE=DATE:20261024
                END:VEVENT
                BEGIN:VEVENT
                UID:b
                DTSTART;VALUE=DATE:20270301
                DTEND;VALUE=DATE:20270305
                END:VEVENT
                BEGIN:VEVENT
                UID:c
                STATUS:CANCELLED
                DTSTART;VALUE=DATE:20270601
                DTEND;VALUE=DATE:20270605
                END:VEVENT
                END:VCALENDAR
                """;
        FeedChecker.Result found = FeedChecker.read(ics, today);
        assertThat(found.outcome()).isEqualTo(FeedChecker.Outcome.FOUND);
        assertThat(found.periods()).isEqualTo(2);
        assertThat(found.until()).isEqualTo(LocalDate.of(2027, 3, 5));
        assertThat(OnboardingTexts.feedMessage(found, today))
                .isEqualTo("Нашли 2 занятых периода до марта 2027. Ссылка сохранена.");

        assertThat(FeedChecker.read("BEGIN:VCALENDAR\nEND:VCALENDAR\n", today).outcome()).isEqualTo(FeedChecker.Outcome.EMPTY);
        assertThat(FeedChecker.read("<html><body>Войдите в кабинет</body></html>", today).outcome())
                .isEqualTo(FeedChecker.Outcome.NOT_CALENDAR);
        assertThat(FeedChecker.read(null, today).ok()).isFalse();
    }

    @Test
    @DisplayName("слова для человека: «1 занятый период», «7 занятых периодов», «до марта»")
    void wording() {
        assertThat(OnboardingTexts.periods(1)).isEqualTo("1 занятый период");
        assertThat(OnboardingTexts.periods(3)).isEqualTo("3 занятых периода");
        assertThat(OnboardingTexts.periods(7)).isEqualTo("7 занятых периодов");
        assertThat(OnboardingTexts.periods(11)).isEqualTo("11 занятых периодов");
        assertThat(OnboardingTexts.periods(21)).isEqualTo("21 занятый период");
        LocalDate today = LocalDate.of(2026, 10, 13);
        assertThat(OnboardingTexts.until(LocalDate.of(2026, 12, 3), today)).isEqualTo("до декабря");
        assertThat(OnboardingTexts.until(LocalDate.of(2027, 3, 1), today)).isEqualTo("до марта 2027");
        assertThat(OnboardingTexts.feedMessage(new FeedChecker.Result(FeedChecker.Outcome.UNREACHABLE, 0, null), today))
                .isEqualTo("Ссылка не открывается — проверьте, что скопировали её целиком.");
    }
}
