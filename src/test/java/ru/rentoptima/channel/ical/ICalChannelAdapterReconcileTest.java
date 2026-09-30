package ru.rentoptima.channel.ical;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.rentoptima.channel.ChannelContext;
import ru.rentoptima.channel.ChannelSyncResult;
import ru.rentoptima.entity.CalendarBlock;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.CalendarBlockRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Единичный тест на reconcile-логику. Без Spring, без БД — только логика адаптера.
 * <p>
 * Сценарии:
 * <ul>
 *   <li>новое событие → создаётся CalendarBlock;</li>
 *   <li>событие с тем же UID и датами → save не вызывается;</li>
 *   <li>событие с тем же UID и другими датами → save вызывается один раз;</li>
 *   <li>блокировка есть в БД, но исчезла из фида → удаляется;</li>
 *   <li>событие с STATUS:CANCELLED → не импортируется;</li>
 *   <li>событие вне окна [from, to) → пропускается.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ICalChannelAdapter.reconcile: идемпотентность")
class ICalChannelAdapterReconcileTest {

    private static final Long CHANNEL_ID = 100L;
    private static final Long UNIT_TYPE_ID = 10L;
    private static final Long TENANT_ID = 1L;

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 1);

    @Mock ICalFeedFetcher fetcher;
    @Mock CalendarBlockRepository blockRepo;

    ICalChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ICalChannelAdapter(fetcher, blockRepo);
    }

    private ChannelContext ctx() {
        Channel c = new Channel();
        c.setId(CHANNEL_ID);
        c.setTenantId(TENANT_ID);
        c.setUnitTypeId(UNIT_TYPE_ID);
        c.setName("test-ical");
        c.setChannelType(Channel.ChannelType.ICAL);

        UnitType ut = new UnitType();
        ut.setId(UNIT_TYPE_ID);
        ut.setTenantId(TENANT_ID);
        ut.setName("Основной");
        ut.setUnitCount(1);

        Property p = new Property();
        p.setName("Test");

        return new ChannelContext(c, ut, p, FROM, TO);
    }

    private ICalEvent event(String uid, LocalDate start, LocalDate end, boolean cancelled) {
        return new ICalEvent(uid, start, end, "test-summary", cancelled);
    }

    @Test
    @DisplayName("новое событие → создаётся блокировка")
    void newEvent_creates() {
        when(blockRepo.findByChannelIdAndExternalUid(eq(CHANNEL_ID), anyString()))
                .thenReturn(Optional.empty());
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any()))
                .thenReturn(List.of());

        ChannelSyncResult result = adapter.reconcile(
                ctx(),
                List.of(event("uid-A", LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12), false))
        );

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.removed()).isZero();

        ArgumentCaptor<CalendarBlock> captor = ArgumentCaptor.forClass(CalendarBlock.class);
        verify(blockRepo).save(captor.capture());
        CalendarBlock saved = captor.getValue();
        assertThat(saved.getExternalUid()).isEqualTo("uid-A");
        assertThat(saved.getChannelId()).isEqualTo(CHANNEL_ID);
        assertThat(saved.getUnitTypeId()).isEqualTo(UNIT_TYPE_ID);
        assertThat(saved.getBlockType()).isEqualTo(CalendarBlock.BlockType.CHANNEL_SYNC);
        assertThat(saved.getFromDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(saved.getToDate()).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    @DisplayName("та же занятость → save не вызывается (полностью идемпотентно)")
    void unchanged_noWrite() {
        LocalDate start = LocalDate.of(2026, 10, 10);
        LocalDate end = LocalDate.of(2026, 10, 12);

        CalendarBlock existing = new CalendarBlock();
        existing.setId(999L);
        existing.setChannelId(CHANNEL_ID);
        existing.setExternalUid("uid-A");
        existing.setUnitTypeId(UNIT_TYPE_ID);
        existing.setBlockType(CalendarBlock.BlockType.CHANNEL_SYNC);
        existing.setFromDate(start);
        existing.setToDate(end);
        existing.setReason("test-summary");

        when(blockRepo.findByChannelIdAndExternalUid(CHANNEL_ID, "uid-A"))
                .thenReturn(Optional.of(existing));
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any()))
                .thenReturn(List.of(existing));

        ChannelSyncResult result = adapter.reconcile(ctx(), List.of(event("uid-A", start, end, false)));

        assertThat(result.imported()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.removed()).isZero();
        verify(blockRepo, never()).save(any());
        verify(blockRepo, never()).delete(any());
    }

    @Test
    @DisplayName("сдвиг дат в фиде → save один раз с новыми датами")
    void datesShifted_updatesOnce() {
        LocalDate oldStart = LocalDate.of(2026, 10, 10);
        LocalDate oldEnd = LocalDate.of(2026, 10, 12);
        LocalDate newStart = LocalDate.of(2026, 10, 11);
        LocalDate newEnd = LocalDate.of(2026, 10, 13);

        CalendarBlock existing = new CalendarBlock();
        existing.setChannelId(CHANNEL_ID);
        existing.setExternalUid("uid-A");
        existing.setUnitTypeId(UNIT_TYPE_ID);
        existing.setBlockType(CalendarBlock.BlockType.CHANNEL_SYNC);
        existing.setFromDate(oldStart);
        existing.setToDate(oldEnd);
        existing.setReason("test-summary");

        when(blockRepo.findByChannelIdAndExternalUid(CHANNEL_ID, "uid-A"))
                .thenReturn(Optional.of(existing));
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any()))
                .thenReturn(List.of(existing));

        ChannelSyncResult result = adapter.reconcile(ctx(),
                List.of(event("uid-A", newStart, newEnd, false)));

        assertThat(result.imported()).isZero();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.removed()).isZero();
        verify(blockRepo, times(1)).save(any());
        assertThat(existing.getFromDate()).isEqualTo(newStart);
        assertThat(existing.getToDate()).isEqualTo(newEnd);
    }

    @Test
    @DisplayName("блок был в БД, из фида исчез → удаляется")
    void disappeared_removed() {
        CalendarBlock existing = new CalendarBlock();
        existing.setChannelId(CHANNEL_ID);
        existing.setExternalUid("uid-gone");
        existing.setUnitTypeId(UNIT_TYPE_ID);
        existing.setFromDate(LocalDate.of(2026, 10, 10));
        existing.setToDate(LocalDate.of(2026, 10, 12));

        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any()))
                .thenReturn(List.of(existing));

        ChannelSyncResult result = adapter.reconcile(ctx(), List.of());

        assertThat(result.removed()).isEqualTo(1);
        verify(blockRepo).delete(existing);
    }

    @Test
    @DisplayName("STATUS:CANCELLED → не импортируется")
    void cancelled_notImported() {
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any()))
                .thenReturn(List.of());

        ChannelSyncResult result = adapter.reconcile(ctx(),
                List.of(event("uid-C", LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12), true)));

        assertThat(result.imported()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        verify(blockRepo, never()).save(any());
    }

    @Test
    @DisplayName("вне окна [from, to) → skipped, save не вызывается")
    void outOfWindow_skipped() {
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any()))
                .thenReturn(List.of());

        ChannelSyncResult result = adapter.reconcile(ctx(),
                List.of(
                        event("uid-before", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3), false),
                        event("uid-after",  LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3), false)
                ));

        assertThat(result.imported()).isZero();
        assertThat(result.skipped()).isEqualTo(2);
        verify(blockRepo, never()).save(any());
    }

    @Test
    @DisplayName("ручная блокировка (external_uid IS NULL) не трогается при reconcile")
    void manualBlock_untouched() {
        CalendarBlock manual = new CalendarBlock();
        manual.setChannelId(CHANNEL_ID); // канал есть, но uid нет
        manual.setExternalUid(null);
        manual.setUnitTypeId(UNIT_TYPE_ID);
        manual.setFromDate(LocalDate.of(2026, 10, 10));
        manual.setToDate(LocalDate.of(2026, 10, 12));

        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any()))
                .thenReturn(List.of(manual));

        ChannelSyncResult result = adapter.reconcile(ctx(), List.of());

        assertThat(result.removed()).isZero();
        verify(blockRepo, never()).delete(any());
    }
}
