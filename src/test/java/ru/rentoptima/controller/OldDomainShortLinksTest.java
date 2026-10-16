package ru.rentoptima.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.entity.WidgetQrLink;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.FeedbackAnswerRepository;
import ru.rentoptima.repository.FeedbackQuestionRepository;
import ru.rentoptima.repository.FeedbackResponseRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.seo.SiteSettings;
import ru.rentoptima.auth.EmailVerificationService;
import ru.rentoptima.service.PublicRateLimiter;
import ru.rentoptima.service.TelegramService;
import ru.rentoptima.service.WidgetBookingService;
import ru.rentoptima.service.WidgetCardService;
import ru.rentoptima.service.WidgetConfigService;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * После переезда prod на optirent.ru прежний staging.optirent.ru обслуживает только /ical/,
 * /q/ и /f/ — с этим доменом уже напечатаны карточки. Короткие ссылки должны вести на
 * основной домен из APP_BASE_URL.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Короткие ссылки со старого домена ведут на APP_BASE_URL")
class OldDomainShortLinksTest {

    private static final SiteSettings PROD = new SiteSettings("prod", "https://optirent.ru", "", "", "", "");

    @Mock WidgetBookingService widgets;
    @Mock WidgetConfigService configs;
    @Mock EmailVerificationService verification;
    @Mock WidgetCardService cards;
    @Mock UnitTypeRepository unitTypeRepo;
    @Mock PropertyRepository propertyRepo;
    @Mock BookingRepository bookingRepo;
    @Mock PublicRateLimiter rateLimiter;
    @Mock TelegramService telegram;
    @Mock FeedbackResponseRepository feedbackRepo;
    @Mock FeedbackQuestionRepository questionRepo;
    @Mock FeedbackAnswerRepository answerRepo;

    private static MockHttpServletRequest request(String host, String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
        r.setScheme("https");
        r.setServerName(host);
        r.setServerPort(443);
        return r;
    }

    private WidgetQrLink link(String kind) {
        WidgetQrLink link = new WidgetQrLink();
        link.setKind(kind);
        link.setWidgetId(5L);
        BookingWidget w = new BookingWidget();
        w.setUnitTypeId(9L);
        when(cards.findLink("abc1234")).thenReturn(Optional.of(link));
        when(widgets.findById(5L)).thenReturn(Optional.of(w));
        return link;
    }

    @Test
    @DisplayName("/q/ на старом домене — сразу полный адрес страницы на основном")
    void cardLinkFromOldDomain() {
        link(WidgetQrLink.KIND_CARD);
        when(cards.target(any(), any(), any(LocalDate.class))).thenReturn("/b/sadovaya?utm_source=qr_card&utm_medium=print");
        var controller = new WidgetPublicController(widgets, configs, verification, cards, PROD, unitTypeRepo, propertyRepo);

        var response = controller.shortLink("abc1234", request("staging.optirent.ru", "/q/abc1234"));

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo("https://optirent.ru/b/sadovaya?utm_source=qr_card&utm_medium=print");
    }

    private FeedbackController feedback() {
        UnitType ut = new UnitType();
        ut.setPropertyId(3L);
        when(unitTypeRepo.findById(9L)).thenReturn(Optional.of(ut));
        when(propertyRepo.findById(3L)).thenReturn(Optional.of(new Property()));
        return new FeedbackController(propertyRepo, unitTypeRepo, bookingRepo, cards, widgets, rateLimiter,
                telegram, feedbackRepo, questionRepo, answerRepo, PROD);
    }

    @Test
    @DisplayName("/f/ на старом домене — редирект на ту же анкету на основном")
    void feedbackFromOldDomain() {
        link(WidgetQrLink.KIND_FEEDBACK);
        String view = feedback().cardFeedbackPage("abc1234", new ExtendedModelMap(),
                request("staging.optirent.ru", "/f/abc1234"));
        assertThat(view).isEqualTo("redirect:https://optirent.ru/f/abc1234");
    }

    @Test
    @DisplayName("/f/ на основном домене — сама анкета")
    void feedbackOnMainDomain() {
        link(WidgetQrLink.KIND_FEEDBACK);
        String view = feedback().cardFeedbackPage("abc1234", new ExtendedModelMap(),
                request("optirent.ru", "/f/abc1234"));
        assertThat(view).isEqualTo("pages/feedback/card");
    }
}
