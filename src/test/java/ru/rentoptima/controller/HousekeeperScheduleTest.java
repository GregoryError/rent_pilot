package ru.rentoptima.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.rentoptima.entity.Booking;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.BookingRepository;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.FeedbackAnswerRepository;
import ru.rentoptima.repository.FeedbackQuestionRepository;
import ru.rentoptima.repository.FeedbackResponseRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Страница горничной: горизонт уборок и открытые даты")
class HousekeeperScheduleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    @Mock PropertyRepository propertyRepo;
    @Mock BookingRepository bookingRepo;
    @Mock CalendarBlockRepository blockRepo;
    @Mock UnitTypeRepository unitTypeRepo;
    @Mock ChannelRepository channelRepo;
    @Mock FeedbackResponseRepository feedbackRepo;
    @Mock FeedbackAnswerRepository answerRepo;
    @Mock FeedbackQuestionRepository questionRepo;

    HousekeeperController controller;
    Property property;
    final List<Booking> bookings = new ArrayList<>();
    final List<CalendarBlock> blocks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        controller = new HousekeeperController(propertyRepo, bookingRepo, blockRepo, unitTypeRepo,
                channelRepo, feedbackRepo, answerRepo, questionRepo,
                org.mockito.Mockito.mock(ru.rentoptima.legal.GuestOperators.class));
        property = new Property();
        property.setId(5L);
        property.setName("Садовая 5 кв. 38");
        UnitType unit = new UnitType();
        unit.setId(9L);
        when(unitTypeRepo.findByPropertyIdAndActiveTrue(5L)).thenReturn(List.of(unit));
        when(bookingRepo.findUpcomingCheckouts(5L, TODAY)).thenReturn(bookings);
        when(blockRepo.findByUnitTypesInRange(anyList(), any(), any())).thenReturn(blocks);
    }

    private Booking booking(int inDays, int outDays) {
        Booking b = new Booking();
        b.setCheckIn(TODAY.plusDays(inDays));
        b.setCheckOut(TODAY.plusDays(outDays));
        b.setGuestName("А.");
        bookings.add(b);
        return b;
    }

    private CalendarBlock block(int fromDays, int toDays) {
        CalendarBlock b = new CalendarBlock();
        b.setBlockType(CalendarBlock.BlockType.CHANNEL_SYNC);
        b.setFromDate(TODAY.plusDays(fromDays));
        b.setToDate(TODAY.plusDays(toDays));
        blocks.add(b);
        return b;
    }

    private List<LocalDate> checkOuts() {
        return controller.upcomingStays(property, TODAY).stream()
                .map(HousekeeperController.Stay::checkOut).toList();
    }

    @Test
    @DisplayName("по умолчанию 30 дней: выезд на 30-й день виден, на 31-й — нет")
    void defaultHorizon() {
        booking(25, 30);
        booking(28, 31);
        block(10, 12);
        block(29, 31);

        assertThat(property.getHousekeeperHorizonDays()).isEqualTo(30);
        assertThat(checkOuts()).containsExactly(TODAY.plusDays(12), TODAY.plusDays(30));
        verify(blockRepo).findByUnitTypesInRange(anyList(), eq(TODAY.minusDays(1)), eq(TODAY.plusDays(30)));
    }

    @Test
    @DisplayName("горизонт из настроек объекта: 7 дней")
    void customHorizon() {
        property.setHousekeeperHorizonDays(7);
        booking(1, 3);
        booking(5, 8);
        block(4, 7);

        assertThat(checkOuts()).containsExactly(TODAY.plusDays(3), TODAY.plusDays(7));
    }

    @Test
    @DisplayName("даты, открытые хозяином («Открыть даты»), горничная не видит")
    void ignoredBlockHidden() {
        block(2, 5).setIgnored(true);
        block(6, 9);

        assertThat(checkOuts()).containsExactly(TODAY.plusDays(9));
    }

    @Test
    @DisplayName("удалённая ручная бронь в график не попадает")
    void cancelledManualBookingHidden() {
        CalendarBlock manual = block(2, 5);
        manual.setBlockType(CalendarBlock.BlockType.MANUAL_BOOKING);
        manual.setCancelledAt(LocalDateTime.now());

        assertThat(checkOuts()).isEmpty();
    }
}
