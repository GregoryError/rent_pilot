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
        when(user.getTenantId()).thenReturn(TENANT_ID);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, null));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("запись помечается cancelled, а не удаляется; хост получает список площадок")
    void softDeleteWithEchoWarning() {
        CalendarBlock manual = new CalendarBlock();
        manual.setId(265L);
        manual.setTenantId(TENANT_ID);
        manual.setUnitTypeId(10L);
        manual.setBlockType(CalendarBlock.BlockType.MANUAL_BOOKING);
        manual.setFromDate(LocalDate.of(2026, 11, 13));
        manual.setToDate(LocalDate.of(2026, 11, 14));

        ManualBlockEcho echo = new ManualBlockEcho();
        echo.setManualBlockId(265L);
        echo.setChannelId(8L);
        echo.setExternalUid("199904867");

        Channel rc = new Channel();
        rc.setId(8L);
        rc.setTenantId(TENANT_ID);
        rc.setName("RealtyCalendar");

        when(blockRepo.findById(265L)).thenReturn(Optional.of(manual));
        when(echoRepo.findByManualBlockId(265L)).thenReturn(List.of(echo));
        when(channelRepo.findAllById(List.of(8L))).thenReturn(List.of(rc));

        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        controller.delete("block:265", null, null, null, redirect);

        assertThat(manual.getCancelledAt()).isNotNull();
        verify(blockRepo).save(manual);
        verify(blockRepo, never()).delete(any());
        assertThat(redirect.getFlashAttributes()).containsKey("success");
        assertThat((String) redirect.getFlashAttributes().get("echoWarning"))
                .contains("RealtyCalendar");
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
