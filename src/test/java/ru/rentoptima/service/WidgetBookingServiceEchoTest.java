package ru.rentoptima.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.PromoCode;
import ru.rentoptima.entity.Tenant;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.BookingWidgetRepository;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.PromoCodeRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.TenantRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.service.WidgetBookingService.Outcome;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Отмена заявки / брони с виджета и её эхо на площадках. Сценарии повторяют
 * GridActionControllerDeleteTest — для ручных записей механизм тот же.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Виджет бронирования: отклонение, истечение и отмена — якорь и тени")
class WidgetBookingServiceEchoTest {

    private static final Long TENANT_ID = 1L;
    private static final Long WIDGET_CHANNEL = 50L;
    private static final String REQUEST_ID = "3f2b8c1e-9a4d-4c7e-8b1a-2d5f6e7a8b9c";

    @Mock BookingWidgetRepository widgetRepo;
    @Mock UnitTypeRepository unitTypeRepo;
    @Mock PropertyRepository propertyRepo;
    @Mock TenantRepository tenantRepo;
    @Mock BookingRepository bookingRepo;
    @Mock CalendarBlockRepository blockRepo;
    @Mock PromoCodeRepository promoRepo;
    @Mock ChannelRepository channelRepo;
    @Mock AvailabilityService availability;
    @Mock EffectivePriceService effectivePrice;

    WidgetBookingService service;

    @BeforeEach
    void setUp() {
        service = new WidgetBookingService(widgetRepo, unitTypeRepo, propertyRepo, tenantRepo, bookingRepo,
                blockRepo, promoRepo, availability, effectivePrice,
                new EchoShadowService(blockRepo, bookingRepo, channelRepo));
    }

    private static Booking booking(String status) {
        Tenant tenant = new Tenant();
        tenant.setId(TENANT_ID);
        Booking b = new Booking();
        b.setId(900L);
        b.setTenant(tenant);
        b.setChannelId(WIDGET_CHANNEL);
        b.setExternalId(REQUEST_ID);
        b.setDataSource(WidgetBookingService.DATA_SOURCE);
        b.setStatus(status);
        b.setCheckIn(LocalDate.of(2026, 11, 13));
        b.setCheckOut(LocalDate.of(2026, 11, 14));
        return b;
    }

    private static CalendarBlock anchor(CalendarBlock.BlockType type) {
        CalendarBlock a = new CalendarBlock();
        a.setId(70L);
        a.setTenantId(TENANT_ID);
        a.setUnitTypeId(10L);
        a.setChannelId(WIDGET_CHANNEL);
        a.setExternalUid(REQUEST_ID);
        a.setBlockType(type);
        a.setFromDate(LocalDate.of(2026, 11, 13));
        a.setToDate(LocalDate.of(2026, 11, 14));
        if (type == CalendarBlock.BlockType.HOLD) a.setExpiresAt(LocalDateTime.now().plusHours(20));
        return a;
    }

    private static CalendarBlock shadow(String uid) {
        CalendarBlock s = new CalendarBlock();
        s.setId(267L);
        s.setTenantId(TENANT_ID);
        s.setUnitTypeId(10L);
        s.setChannelId(8L);
        s.setExternalUid(uid);
        s.setBlockType(CalendarBlock.BlockType.CHANNEL_SYNC);
        s.setShadowOfManualId(70L);
        return s;
    }

    private void inDb(Booking b, CalendarBlock anchor) {
        when(bookingRepo.findById(900L)).thenReturn(Optional.of(b));
        when(blockRepo.findByChannelIdAndExternalUid(WIDGET_CHANNEL, REQUEST_ID)).thenReturn(Optional.of(anchor));
    }

    @Test
    @DisplayName("подтверждение: бронь BOOKED, резерв не удаляется, а становится якорем без срока")
    void confirmKeepsAnchor() {
        Booking b = booking(WidgetBookingService.STATUS_PENDING);
        CalendarBlock anchor = anchor(CalendarBlock.BlockType.HOLD);
        inDb(b, anchor);

        Outcome result = service.confirm(TENANT_ID, 900L);

        assertThat(result.ok()).isTrue();
        assertThat(b.getStatus()).isEqualTo(WidgetBookingService.STATUS_BOOKED);
        assertThat(anchor.getBlockType()).isEqualTo(CalendarBlock.BlockType.WIDGET_BOOKING);
        assertThat(anchor.getExpiresAt()).isNull();
        assertThat(anchor.getCancelledAt()).isNull();
        verify(blockRepo, never()).delete(any());
    }

    @Test
    @DisplayName("отклонение: якорь помечается cancelled, а не удаляется; тень с техническим UID скрывается сама")
    void declineSoftCancelsAndHidesShadow() {
        Booking b = booking(WidgetBookingService.STATUS_PENDING);
        CalendarBlock anchor = anchor(CalendarBlock.BlockType.HOLD);
        CalendarBlock shadow = shadow("199904867");
        inDb(b, anchor);
        when(blockRepo.findByShadowOfManualId(70L)).thenReturn(List.of(shadow));
        when(bookingRepo.findByChannelIdAndExternalId(8L, "199904867")).thenReturn(Optional.empty());

        Outcome result = service.decline(TENANT_ID, 900L);

        assertThat(result.ok()).isTrue();
        assertThat(result.keptShadows()).isEmpty();
        assertThat(b.getStatus()).isEqualTo(WidgetBookingService.STATUS_DECLINED);
        assertThat(anchor.getCancelledAt()).isNotNull();
        assertThat(anchor.getExpiresAt()).isNull();
        assertThat(shadow.getIgnored()).isTrue();
        assertThat(shadow.getShadowOfManualId()).isEqualTo(70L);
        verify(blockRepo).save(shadow);
        verify(blockRepo, never()).delete(any());
    }

    @Test
    @DisplayName("отмена подтверждённой брони: за тенью бронь с именем гостя → тень остаётся, хозяин предупреждён")
    void cancelKeepsShadowWithGuest() {
        Booking b = booking(WidgetBookingService.STATUS_BOOKED);
        CalendarBlock anchor = anchor(CalendarBlock.BlockType.WIDGET_BOOKING);
        CalendarBlock shadow = shadow("199904867");
        Booking real = new Booking();
        real.setGuestName("Иван Петров");
        Channel channel = new Channel();
        channel.setId(8L);
        channel.setTenantId(TENANT_ID);
        channel.setName("Суточно");
        inDb(b, anchor);
        when(blockRepo.findByShadowOfManualId(70L)).thenReturn(List.of(shadow));
        when(bookingRepo.findByChannelIdAndExternalId(8L, "199904867")).thenReturn(Optional.of(real));
        when(channelRepo.findById(8L)).thenReturn(Optional.of(channel));

        Outcome result = service.cancel(TENANT_ID, 900L);

        assertThat(b.getStatus()).isEqualTo(WidgetBookingService.STATUS_CANCELLED);
        assertThat(anchor.getCancelledAt()).isNotNull();
        assertThat(shadow.getIgnored()).isFalse();
        verify(blockRepo, never()).save(shadow);
        assertThat(result.keptShadows()).singleElement().asString()
                .contains("Суточно").contains("с именем гостя");
    }

    @Test
    @DisplayName("отмена: UID тени не похож на технический → тень остаётся")
    void cancelKeepsShadowWithUnusualUid() {
        Booking b = booking(WidgetBookingService.STATUS_BOOKED);
        CalendarBlock shadow = shadow("booking-ivan-petrov@example.com");
        inDb(b, anchor(CalendarBlock.BlockType.WIDGET_BOOKING));
        when(blockRepo.findByShadowOfManualId(70L)).thenReturn(List.of(shadow));
        when(bookingRepo.findByChannelIdAndExternalId(8L, "booking-ivan-petrov@example.com"))
                .thenReturn(Optional.empty());
        when(channelRepo.findById(8L)).thenReturn(Optional.empty());

        Outcome result = service.cancel(TENANT_ID, 900L);

        assertThat(shadow.getIgnored()).isFalse();
        assertThat(result.keptShadows()).hasSize(1);
    }

    @Test
    @DisplayName("истечение срока: заявка EXPIRED, якорь cancelled, тень скрыта — без участия хозяина")
    void expireSoftCancelsAndHidesShadow() {
        Booking b = booking(WidgetBookingService.STATUS_PENDING);
        CalendarBlock anchor = anchor(CalendarBlock.BlockType.HOLD);
        anchor.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        CalendarBlock shadow = shadow("199904867");
        when(blockRepo.findByExpiresAtBefore(any())).thenReturn(List.of(anchor));
        when(bookingRepo.findByChannelIdAndExternalId(WIDGET_CHANNEL, REQUEST_ID)).thenReturn(Optional.of(b));
        when(blockRepo.findByChannelIdAndExternalUid(WIDGET_CHANNEL, REQUEST_ID)).thenReturn(Optional.of(anchor));
        when(blockRepo.findByShadowOfManualId(70L)).thenReturn(List.of(shadow));
        when(bookingRepo.findByChannelIdAndExternalId(8L, "199904867")).thenReturn(Optional.empty());

        service.expireHolds();

        assertThat(b.getStatus()).isEqualTo(WidgetBookingService.STATUS_EXPIRED);
        assertThat(anchor.getCancelledAt()).isNotNull();
        assertThat(anchor.getExpiresAt()).isNull();
        assertThat(shadow.getIgnored()).isTrue();
        verify(blockRepo, never()).delete(any());
    }

    @Test
    @DisplayName("отклонённая заявка возвращает применение промокода")
    void declineReleasesPromoUse() {
        Booking b = booking(WidgetBookingService.STATUS_PENDING);
        b.setPromoCodeId(5L);
        PromoCode promo = new PromoCode();
        promo.setUsedCount(3);
        inDb(b, anchor(CalendarBlock.BlockType.HOLD));
        when(promoRepo.lockById(5L)).thenReturn(Optional.of(promo));

        service.decline(TENANT_ID, 900L);

        assertThat(promo.getUsedCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("чужая или уже обработанная заявка не трогается")
    void foreignOrProcessed() {
        Booking b = booking(WidgetBookingService.STATUS_DECLINED);
        when(bookingRepo.findById(900L)).thenReturn(Optional.of(b));

        assertThat(service.decline(TENANT_ID, 900L).ok()).isFalse();
        assertThat(service.confirm(2L, 900L).ok()).isFalse();
        verify(blockRepo, never()).save(any());
    }
}
