package ru.rentoptima.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.ManualBlockEcho;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.ManualBlockEchoRepository;
import ru.rentoptima.repository.PlannedPriceRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.TenantRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.security.TenantUserDetails;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Шахматка: удаление ручной записи с эхо на площадках")
class GridActionControllerDeleteTest {

    private static final Long TENANT_ID = 1L;

    @Mock UnitTypeRepository unitTypeRepo;
    @Mock PropertyRepository propertyRepo;
    @Mock TenantRepository tenantRepo;
    @Mock BookingRepository bookingRepo;
    @Mock CalendarBlockRepository blockRepo;
    @Mock ChannelRepository channelRepo;
    @Mock ManualBlockEchoRepository echoRepo;
    @Mock PlannedPriceRepository plannedPriceRepo;

    @InjectMocks GridActionController controller;

    @BeforeEach
    void signIn() {
        TenantUserDetails user = mock(TenantUserDetails.class);
        lenient().when(user.getTenantId()).thenReturn(TENANT_ID);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, null));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static CalendarBlock manual() {
        CalendarBlock manual = new CalendarBlock();
        manual.setId(265L);
        manual.setTenantId(TENANT_ID);
        manual.setUnitTypeId(10L);
        manual.setBlockType(CalendarBlock.BlockType.MAINTENANCE);
        manual.setFromDate(LocalDate.of(2026, 11, 13));
        manual.setToDate(LocalDate.of(2026, 11, 14));
        return manual;
    }

    private static CalendarBlock shadow(String uid) {
        CalendarBlock shadow = new CalendarBlock();
        shadow.setId(267L);
        shadow.setTenantId(TENANT_ID);
        shadow.setUnitTypeId(10L);
        shadow.setChannelId(8L);
        shadow.setExternalUid(uid);
        shadow.setBlockType(CalendarBlock.BlockType.CHANNEL_SYNC);
        shadow.setShadowOfManualId(265L);
        return shadow;
    }

    private static Channel channel() {
        Channel channel = new Channel();
        channel.setId(8L);
        channel.setTenantId(TENANT_ID);
        channel.setName("Суточно");
        return channel;
    }

    private RedirectAttributesModelMap delete() {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        controller.delete("block:265", null, null, null, redirect);
        return redirect;
    }

    @Test
    @DisplayName("запись помечается cancelled, а не удаляется; без теней — просто «Запись удалена», без модалки")
    void softDeleteWithoutWarning() {
        CalendarBlock manual = manual();
        ManualBlockEcho echo = new ManualBlockEcho();
        echo.setManualBlockId(265L);
        echo.setChannelId(8L);
        echo.setExternalUid("optirent-manual-265@optirent.ru");

        when(blockRepo.findById(265L)).thenReturn(Optional.of(manual));
        when(echoRepo.findByManualBlockId(265L)).thenReturn(List.of(echo));
        when(channelRepo.findAllById(List.of(8L))).thenReturn(List.of(channel()));

        RedirectAttributesModelMap redirect = delete();

        assertThat(manual.getCancelledAt()).isNotNull();
        verify(blockRepo).save(manual);
        verify(blockRepo, never()).delete(any());
        assertThat(redirect.getFlashAttributes().get("success")).isEqualTo("Запись удалена");
        assertThat(redirect.getFlashAttributes()).doesNotContainKey("echoWarning");
    }

    @Test
    @DisplayName("тень с техническим UID скрывается автоматически (ignored), физически не удаляется, модалки нет")
    void technicalShadowIsAutoIgnored() {
        CalendarBlock manual = manual();
        CalendarBlock shadow = shadow("199904867");

        when(blockRepo.findById(265L)).thenReturn(Optional.of(manual));
        when(blockRepo.findByShadowOfManualId(265L)).thenReturn(List.of(shadow));
        when(bookingRepo.findByChannelIdAndExternalId(8L, "199904867")).thenReturn(Optional.empty());

        RedirectAttributesModelMap redirect = delete();

        assertThat(manual.getCancelledAt()).isNotNull();
        assertThat(shadow.getIgnored()).isTrue();
        assertThat(shadow.getShadowOfManualId()).isEqualTo(265L);
        verify(blockRepo).save(shadow);
        verify(blockRepo, never()).delete(any());
        assertThat(redirect.getFlashAttributes().get("success")).isEqualTo("Запись удалена");
        assertThat(redirect.getFlashAttributes()).doesNotContainKey("echoWarning");

        // Выборка занятости (findByUnitTypesInRange) отсекает и cancelled_at, и ignored —
        // после удаления день в шахматке пуст.
    }

    @Test
    @DisplayName("за тенью стоит бронь с именем гостя → тень остаётся, модалка предупреждает")
    void shadowWithGuestIsKept() {
        CalendarBlock manual = manual();
        CalendarBlock shadow = shadow("199904867");
        Booking real = new Booking();
        real.setGuestName("Иван Петров");

        when(blockRepo.findById(265L)).thenReturn(Optional.of(manual));
        when(blockRepo.findByShadowOfManualId(265L)).thenReturn(List.of(shadow));
        when(bookingRepo.findByChannelIdAndExternalId(8L, "199904867")).thenReturn(Optional.of(real));
        when(channelRepo.findById(8L)).thenReturn(Optional.of(channel()));

        RedirectAttributesModelMap redirect = delete();

        assertThat(manual.getCancelledAt()).isNotNull();
        assertThat(shadow.getIgnored()).isFalse();
        verify(blockRepo, never()).save(shadow);
        verify(blockRepo, never()).delete(any());
        assertThat((String) redirect.getFlashAttributes().get("success")).contains("остаются закрытыми");
        assertThat((String) redirect.getFlashAttributes().get("echoWarning"))
                .contains("Суточно").contains("с именем гостя");
    }

    @Test
    @DisplayName("UID тени не похож на технический → тень остаётся, модалка предупреждает")
    void shadowWithUnusualUidIsKept() {
        CalendarBlock manual = manual();
        CalendarBlock shadow = shadow("booking-ivan-petrov@example.com");

        when(blockRepo.findById(265L)).thenReturn(Optional.of(manual));
        when(blockRepo.findByShadowOfManualId(265L)).thenReturn(List.of(shadow));
        when(bookingRepo.findByChannelIdAndExternalId(8L, "booking-ivan-petrov@example.com"))
                .thenReturn(Optional.empty());
        when(channelRepo.findById(8L)).thenReturn(Optional.of(channel()));

        RedirectAttributesModelMap redirect = delete();

        assertThat(shadow.getIgnored()).isFalse();
        assertThat((String) redirect.getFlashAttributes().get("echoWarning")).contains("Суточно");
    }

    @Test
    @DisplayName("технический UID: число, hex-хэш, UUID, в том числе с доменом площадки")
    void technicalUid() {
        assertThat(GridActionController.looksTechnicalUid("199904867")).isTrue();
        assertThat(GridActionController.looksTechnicalUid("199904867@realty.example")).isTrue();
        assertThat(GridActionController.looksTechnicalUid("a3f9c21b7d")).isTrue();
        assertThat(GridActionController.looksTechnicalUid("3f2b8c1e-9a4d-4c7e-8b1a-2d5f6e7a8b9c")).isTrue();
        assertThat(GridActionController.looksTechnicalUid("booking-ivan-petrov")).isFalse();
        assertThat(GridActionController.looksTechnicalUid("Иванов 13-14 ноября")).isFalse();
        assertThat(GridActionController.looksTechnicalUid(null)).isFalse();
    }

    @Test
    @DisplayName("уже удалённую запись повторно удалить нельзя")
    void alreadyCancelled() {
        CalendarBlock manual = new CalendarBlock();
        manual.setId(265L);
        manual.setTenantId(TENANT_ID);
        manual.setBlockType(CalendarBlock.BlockType.MANUAL_BOOKING);
        manual.setCancelledAt(java.time.LocalDateTime.now());
        when(blockRepo.findById(265L)).thenReturn(Optional.of(manual));

        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        controller.delete("block:265", null, null, null, redirect);

        verify(blockRepo, never()).save(any());
        assertThat(redirect.getFlashAttributes()).containsKey("error");
    }
}
