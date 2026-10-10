package ru.rentoptima.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.BookingWidget;
import ru.rentoptima.entity.PromoCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Виджет бронирования: адрес, разрешённые сайты, токен формы, промокод, .ics")
class WidgetHelpersTest {

    @Test
    @DisplayName("адрес страницы строится из названия транслитом")
    void slugFromTitle() {
        assertThat(WidgetSlug.fromTitle("Квартира на Садовой, 12")).isEqualTo("kvartira-na-sadovoy-12");
        assertThat(WidgetSlug.fromTitle("  Loft #1  ")).isEqualTo("loft-1");
        assertThat(WidgetSlug.fromTitle("🏠")).isEqualTo("booking");
        assertThat(WidgetSlug.valid("kvartira-na-sadovoy-12")).isTrue();
        assertThat(WidgetSlug.valid("Kvartira")).isFalse();
        assertThat(WidgetSlug.valid("a-")).isFalse();
        assertThat(WidgetSlug.valid("../admin")).isFalse();
    }

    @Test
    @DisplayName("занятый адрес получает суффикс")
    void slugUnique() {
        Set<String> taken = Set.of("loft", "loft-2");
        assertThat(WidgetSlug.unique("loft", taken::contains)).isEqualTo("loft-3");
        assertThat(WidgetSlug.unique("studio", taken::contains)).isEqualTo("studio");
    }

    @Test
    @DisplayName("сайты приводятся к origin: без пути, https по умолчанию, мусор отбрасывается")
    void originsNormalize() {
        String stored = WidgetOrigins.normalize("""
                mysite.ru
                https://WWW.Example.com/booking?x=1
                http://shop.example.com
                http://localhost:3000
                javascript:alert(1)
                not a site
                mysite.ru
                """);
        assertThat(WidgetOrigins.parse(stored)).containsExactly(
                "https://mysite.ru", "https://www.example.com", "https://shop.example.com",
                "http://localhost:3000");
        assertThat(WidgetOrigins.frameAncestors(stored))
                .startsWith("frame-ancestors 'self' https://mysite.ru ");
        assertThat(WidgetOrigins.frameAncestors("")).isEqualTo("frame-ancestors 'self'");
    }

    @Test
    @DisplayName("звёздочка и подстановки в список сайтов не проходят")
    void originsNoWildcard() {
        assertThat(WidgetOrigins.normalize("*")).isEmpty();
        assertThat(WidgetOrigins.normalize("https://*.example.com")).isEmpty();
        assertThat(WidgetOrigins.normalize("null")).isEmpty();
    }

    @Test
    @DisplayName("токен формы: слишком быстро — отказ, через 3 секунды — годен, чужой и подделанный — нет")
    void formToken() {
        WidgetFormToken tokens = new WidgetFormToken("secret");
        long issued = 1_000_000L;
        String token = tokens.issue(7L, issued);

        assertThat(tokens.check(7L, token, issued + 500)).isEqualTo(WidgetFormToken.Check.TOO_FAST);
        assertThat(tokens.check(7L, token, issued + 3_000)).isEqualTo(WidgetFormToken.Check.OK);
        assertThat(tokens.check(7L, token, issued + 13 * 3_600_000L)).isEqualTo(WidgetFormToken.Check.EXPIRED);
        assertThat(tokens.check(8L, token, issued + 3_000)).isEqualTo(WidgetFormToken.Check.INVALID);
        assertThat(tokens.check(7L, (issued - 60_000) + token.substring(token.indexOf('.')), issued + 3_000))
                .isEqualTo(WidgetFormToken.Check.INVALID);
        assertThat(tokens.check(7L, null, issued)).isEqualTo(WidgetFormToken.Check.INVALID);
        assertThat(new WidgetFormToken("other").check(7L, token, issued + 3_000))
                .isEqualTo(WidgetFormToken.Check.INVALID);
        // Тот же секрет после перезапуска приложения — токен по-прежнему годен
        assertThat(new WidgetFormToken("secret").check(7L, token, issued + 3_000))
                .isEqualTo(WidgetFormToken.Check.OK);
    }

    @Test
    @DisplayName("промокод: выключенный, ещё не начавшийся, истёкший, исчерпанный")
    void promoCheck() {
        LocalDate today = LocalDate.of(2026, 11, 10);
        PromoCode p = new PromoCode();
        p.setCode("LETO");
        assertThat(WidgetBookingService.checkPromo(p, today).promo()).isSameAs(p);
        assertThat(WidgetBookingService.checkPromo(null, today).error()).isEqualTo(WidgetError.PROMO_INVALID);

        p.setValidUntil(today);
        assertThat(WidgetBookingService.checkPromo(p, today).error()).isNull();
        p.setValidUntil(today.minusDays(1));
        assertThat(WidgetBookingService.checkPromo(p, today).error()).isEqualTo(WidgetError.PROMO_EXPIRED);

        p.setValidUntil(null);
        p.setMaxUses(2);
        p.setUsedCount(2);
        assertThat(WidgetBookingService.checkPromo(p, today).error()).isEqualTo(WidgetError.PROMO_EXHAUSTED);

        p.setMaxUses(null);
        p.setActive(false);
        assertThat(WidgetBookingService.checkPromo(p, today).error()).isEqualTo(WidgetError.PROMO_INVALID);

        assertThat(PromoCode.normalize("  leto 10 ")).isEqualTo("LETO10");
        assertThat(PromoCode.normalize("  ")).isNull();
    }

    @Test
    @DisplayName("напоминание хозяину — только если резерв длиннее четырёх часов")
    void reminder() {
        LocalDateTime created = LocalDateTime.of(2026, 11, 10, 12, 0);
        assertThat(WidgetNotifier.shouldRemind(created, created.plusHours(24))).isTrue();
        assertThat(WidgetNotifier.shouldRemind(created, created.plusHours(4))).isTrue();
        assertThat(WidgetNotifier.shouldRemind(created, created.plusHours(2))).isFalse();
        assertThat(WidgetNotifier.remaining(119)).isEqualTo("1 ч 59 мин");
        assertThat(WidgetNotifier.remaining(45)).isEqualTo("45 мин");
    }

    @Test
    @DisplayName(".ics для гостя: время заезда и выезда, без персональных данных, спецсимволы экранированы")
    void guestCalendar() {
        BookingWidget w = new BookingWidget();
        w.setTitle("Лофт; у парка, центр");
        w.setCheckinTime(LocalTime.of(14, 0));
        w.setCheckoutTime(LocalTime.of(12, 0));
        Booking b = new Booking();
        b.setExternalId("3f2b8c1e-9a4d-4c7e-8b1a-2d5f6e7a8b9c");
        b.setStatus(WidgetBookingService.STATUS_BOOKED);
        b.setCheckIn(LocalDate.of(2026, 11, 13));
        b.setCheckOut(LocalDate.of(2026, 11, 15));
        b.setPublicCode("K7M29QXA");
        b.setGuestName("И.");
        b.setGuestPhone("+79001234567");

        String ics = WidgetGuestCalendar.write(w, b);

        assertThat(ics).contains("DTSTART:20261113T140000\r\n", "DTEND:20261115T120000\r\n",
                "SUMMARY:Лофт\\; у парка\\, центр\r\n", "STATUS:CONFIRMED", "K7M29QXA");
        assertThat(ics).doesNotContain("+79001234567");
        assertThat(ics.lines()).allSatisfy(line ->
                assertThat(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75));
    }

    @Test
    @DisplayName("источник перехода: utm_source, иначе сайт-источник без www, иначе direct; мусор вычищается")
    void funnelSource() {
        assertThat(WidgetFunnelService.source("Telegram", "https://vk.com/feed", "optirent.ru")).isEqualTo("telegram");
        assertThat(WidgetFunnelService.source(null, "https://www.vk.com/feed?x=1", "optirent.ru")).isEqualTo("vk.com");
        assertThat(WidgetFunnelService.source("", "", "optirent.ru")).isEqualTo("direct");
        assertThat(WidgetFunnelService.source(null, null, null)).isEqualTo("direct");
        // Переход внутри того же сайта — не источник
        assertThat(WidgetFunnelService.source(null, "https://mysite.ru/rooms", "mysite.ru")).isEqualTo("direct");
        assertThat(WidgetFunnelService.source("<script>alert(1)</script>", null, null)).isEqualTo("scriptalert1script");
        assertThat(WidgetFunnelService.source("  ", "не ссылка", "x")).isEqualTo("direct");
        assertThat(WidgetFunnelService.source("a".repeat(300), null, null)).hasSize(100);
    }
}
