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
import ru.rentoptima.entity.ManualBlockEcho;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.ManualBlockEchoRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    @Mock ManualBlockEchoRepository echoRepo;

    ICalChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ICalChannelAdapter(fetcher, blockRepo, echoRepo);
    }

    private static final LocalDate ECHO_FROM = LocalDate.of(2026, 11, 13);
    private static final LocalDate ECHO_TO = LocalDate.of(2026, 11, 14);

    /** Ручная бронь на 13–14 ноября, заведённая daysAgo дней назад. */
    private CalendarBlock manual(long id, int daysAgo) {
        CalendarBlock m = new CalendarBlock();
        m.setId(id);
        m.setTenantId(TENANT_ID);
        m.setUnitTypeId(UNIT_TYPE_ID);
        m.setBlockType(CalendarBlock.BlockType.MANUAL_BOOKING);
        m.setFromDate(ECHO_FROM);
        m.setToDate(ECHO_TO);
        m.setCreatedAt(LocalDateTime.now().minusDays(daysAgo));
        return m;
    }

    /** Репозиторий отдаёт только ручные записи моложе запрошенного момента — как настоящий запрос. */
    private void manualBlocksInDb(CalendarBlock... blocks) {
        when(blockRepo.findByUnitTypeIdAndChannelIdIsNullAndCreatedAtAfter(eq(UNIT_TYPE_ID), any()))
                .thenAnswer(inv -> {
                    LocalDateTime since = inv.getArgument(1);
                    return List.of(blocks).stream()
                            .filter(b -> b.getCreatedAt().isAfter(since)).toList();
                });
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

    @Test
    @DisplayName("событие с нашим UID-маркером → блокировка не создаётся, в логе skipped echo")
    void ownUidMarker_skippedAndLogged() {
        Logger logger = (Logger) LoggerFactory.getLogger(ICalChannelAdapter.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            when(blockRepo.findById(123L)).thenReturn(Optional.of(manual(123L, 1)));
            when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any())).thenReturn(List.of());

            ChannelSyncResult result = adapter.reconcile(ctx(), List.of(
                    event("optirent-manual-123@optirent.ru", ECHO_FROM, ECHO_TO, false)));

            assertThat(result.imported()).isZero();
            assertThat(result.skipped()).isEqualTo(1);
            verify(blockRepo, never()).save(any());
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).contains(
                    "Skipped echo of own MANUAL booking: external_uid=optirent-manual-123@optirent.ru, channel="
                            + CHANNEL_ID);

            // заодно запомнили, что запись видели на этом канале
            ArgumentCaptor<ManualBlockEcho> echo = ArgumentCaptor.forClass(ManualBlockEcho.class);
            verify(echoRepo).save(echo.capture());
            assertThat(echo.getValue().getManualBlockId()).isEqualTo(123L);
            assertThat(echo.getValue().getChannelId()).isEqualTo(CHANNEL_ID);
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    @DisplayName("ранее импортированное эхо с нашим UID-маркером снимается при сверке")
    void ownUidMarker_existingEchoBlockRemoved() {
        CalendarBlock stale = new CalendarBlock();
        stale.setChannelId(CHANNEL_ID);
        stale.setExternalUid("optirent-manual-123@optirent.ru");
        stale.setUnitTypeId(UNIT_TYPE_ID);
        stale.setFromDate(ECHO_FROM);
        stale.setToDate(ECHO_TO);

        when(blockRepo.findById(123L)).thenReturn(Optional.empty());
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any())).thenReturn(List.of(stale));

        ChannelSyncResult result = adapter.reconcile(ctx(), List.of(
                event("optirent-manual-123@optirent.ru", ECHO_FROM, ECHO_TO, false)));

        assertThat(result.removed()).isEqualTo(1);
        verify(blockRepo).delete(stale);
        verify(echoRepo, never()).save(any());
    }

    @Test
    @DisplayName("чужой UID, даты совпали со свежей ручной записью → блокировка-тень и связь")
    void foreignUidSameDatesAsRecentManual_importedAsShadow() {
        manualBlocksInDb(manual(265L, 0));
        when(blockRepo.findByChannelIdAndExternalUid(CHANNEL_ID, "199904867"))
                .thenReturn(Optional.empty());
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any())).thenReturn(List.of());

        ChannelSyncResult result = adapter.reconcile(ctx(),
                List.of(event("199904867", ECHO_FROM, ECHO_TO, false)));

        assertThat(result.imported()).isEqualTo(1);
        ArgumentCaptor<CalendarBlock> block = ArgumentCaptor.forClass(CalendarBlock.class);
        verify(blockRepo).save(block.capture());
        assertThat(block.getValue().getChannelId()).isEqualTo(CHANNEL_ID);
        assertThat(block.getValue().getExternalUid()).isEqualTo("199904867");
        assertThat(block.getValue().getBlockType()).isEqualTo(CalendarBlock.BlockType.CHANNEL_SYNC);
        assertThat(block.getValue().getShadowOfManualId()).isEqualTo(265L);

        ArgumentCaptor<ManualBlockEcho> echo = ArgumentCaptor.forClass(ManualBlockEcho.class);
        verify(echoRepo).save(echo.capture());
        assertThat(echo.getValue().getManualBlockId()).isEqualTo(265L);
        assertThat(echo.getValue().getChannelId()).isEqualTo(CHANNEL_ID);
        assertThat(echo.getValue().getExternalUid()).isEqualTo("199904867");
        assertThat(echo.getValue().getTenantId()).isEqualTo(TENANT_ID);
    }

    @Test
    @DisplayName("удалённая ручная запись с теми же датами тенью не обзаводится — обычная блокировка")
    void foreignUidSameDatesAsCancelledManual_plainBlock() {
        CalendarBlock cancelled = manual(265L, 0);
        cancelled.setCancelledAt(LocalDateTime.now());
        manualBlocksInDb(cancelled);
        when(blockRepo.findByChannelIdAndExternalUid(CHANNEL_ID, "199904867"))
                .thenReturn(Optional.empty());
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any())).thenReturn(List.of());

        adapter.reconcile(ctx(), List.of(event("199904867", ECHO_FROM, ECHO_TO, false)));

        ArgumentCaptor<CalendarBlock> block = ArgumentCaptor.forClass(CalendarBlock.class);
        verify(blockRepo).save(block.capture());
        assertThat(block.getValue().getShadowOfManualId()).isNull();
        verify(echoRepo, never()).save(any());
    }

    @Test
    @DisplayName("ручной записи 10 дней → совпадение дат уже не эхо, блокировка создаётся")
    void foreignUidSameDatesAsOldManual_created() {
        manualBlocksInDb(manual(265L, 10));
        when(blockRepo.findByChannelIdAndExternalUid(CHANNEL_ID, "199904867"))
                .thenReturn(Optional.empty());
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any())).thenReturn(List.of());

        ChannelSyncResult result = adapter.reconcile(ctx(),
                List.of(event("199904867", ECHO_FROM, ECHO_TO, false)));

        assertThat(result.imported()).isEqualTo(1);
        verify(blockRepo).save(any(CalendarBlock.class));
        verify(echoRepo, never()).save(any());
    }

    @Test
    @DisplayName("чужой UID на даты без ручной записи → обычная блокировка")
    void foreignUidOtherDates_created() {
        manualBlocksInDb(manual(265L, 0));
        when(blockRepo.findByChannelIdAndExternalUid(CHANNEL_ID, "199904867"))
                .thenReturn(Optional.empty());
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any())).thenReturn(List.of());

        ChannelSyncResult result = adapter.reconcile(ctx(), List.of(
                event("199904867", LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22), false)));

        assertThat(result.imported()).isEqualTo(1);
        verify(blockRepo).save(any(CalendarBlock.class));
        verify(echoRepo, never()).save(any());
    }

    /** Тень ручной записи 265, уже лежащая в базе. */
    private CalendarBlock shadow() {
        CalendarBlock shadow = new CalendarBlock();
        shadow.setId(267L);
        shadow.setChannelId(CHANNEL_ID);
        shadow.setExternalUid("199904867");
        shadow.setUnitTypeId(UNIT_TYPE_ID);
        shadow.setBlockType(CalendarBlock.BlockType.CHANNEL_SYNC);
        shadow.setFromDate(ECHO_FROM);
        shadow.setToDate(ECHO_TO);
        shadow.setReason("test-summary");
        shadow.setShadowOfManualId(265L);
        return shadow;
    }

    private void echoLinkInDb() {
        ManualBlockEcho known = new ManualBlockEcho();
        known.setManualBlockId(265L);
        known.setChannelId(CHANNEL_ID);
        known.setExternalUid("199904867");
        when(echoRepo.findByChannelId(CHANNEL_ID)).thenReturn(List.of(known));
    }

    @Test
    @DisplayName("эхо по датам → ручную запись удалили → тень остаётся, дубликат не создаётся")
    void shadowSurvivesManualDeletion() {
        CalendarBlock shadow = shadow();
        CalendarBlock deletedManual = manual(265L, 1);
        deletedManual.setCancelledAt(LocalDateTime.now());

        echoLinkInDb();
        manualBlocksInDb(deletedManual);
        when(blockRepo.findByChannelIdAndExternalUid(CHANNEL_ID, "199904867"))
                .thenReturn(Optional.of(shadow));
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any()))
                .thenReturn(List.of(shadow));

        ChannelSyncResult result = adapter.reconcile(ctx(),
                List.of(event("199904867", ECHO_FROM, ECHO_TO, false)));

        assertThat(result.imported()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.removed()).isZero();
        verify(blockRepo, never()).save(any());
        verify(blockRepo, never()).delete(any());
        verify(echoRepo, never()).save(any());
        assertThat(shadow.getShadowOfManualId()).isEqualTo(265L);
    }

    @Test
    @DisplayName("площадка сняла событие → тень удаляется обычной сверкой")
    void shadowRemovedWhenEventDisappears() {
        CalendarBlock shadow = shadow();
        echoLinkInDb();
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any()))
                .thenReturn(List.of(shadow));

        ChannelSyncResult result = adapter.reconcile(ctx(), List.of());

        assertThat(result.removed()).isEqualTo(1);
        verify(blockRepo).delete(shadow);
    }

    @Test
    @DisplayName("у тени на площадке сдвинули даты → пометка снимается, это самостоятельная блокировка")
    void shadowWithShiftedDates_becomesPlainBlock() {
        CalendarBlock shadow = shadow();
        echoLinkInDb();
        when(blockRepo.findByChannelIdAndExternalUid(CHANNEL_ID, "199904867"))
                .thenReturn(Optional.of(shadow));
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any()))
                .thenReturn(List.of(shadow));

        ChannelSyncResult result = adapter.reconcile(ctx(),
                List.of(event("199904867", ECHO_FROM, ECHO_TO.plusDays(2), false)));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(shadow.getShadowOfManualId()).isNull();
        assertThat(shadow.getToDate()).isEqualTo(ECHO_TO.plusDays(2));
    }

    @Test
    @DisplayName("привязанное событие без блокировки (исчезало и вернулось) снова становится тенью, даже если записи больше 7 дней")
    void knownEchoWithoutBlock_recreatedAsShadow() {
        echoLinkInDb();
        when(blockRepo.findById(265L)).thenReturn(Optional.of(manual(265L, 20)));
        when(blockRepo.findByChannelIdAndExternalUid(CHANNEL_ID, "199904867"))
                .thenReturn(Optional.empty());
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any())).thenReturn(List.of());

        adapter.reconcile(ctx(), List.of(event("199904867", ECHO_FROM, ECHO_TO, false)));

        ArgumentCaptor<CalendarBlock> block = ArgumentCaptor.forClass(CalendarBlock.class);
        verify(blockRepo).save(block.capture());
        assertThat(block.getValue().getShadowOfManualId()).isEqualTo(265L);
        verify(echoRepo, never()).save(any());
    }

    @Test
    @DisplayName("блокировка, импортированная до ручной записи, эхом не становится")
    void existingChannelBlock_notRelinked() {
        CalendarBlock existing = new CalendarBlock();
        existing.setChannelId(CHANNEL_ID);
        existing.setExternalUid("199904867");
        existing.setUnitTypeId(UNIT_TYPE_ID);
        existing.setBlockType(CalendarBlock.BlockType.CHANNEL_SYNC);
        existing.setFromDate(ECHO_FROM);
        existing.setToDate(ECHO_TO);
        existing.setReason("test-summary");

        manualBlocksInDb(manual(265L, 0));
        when(blockRepo.findByChannelIdAndExternalUid(CHANNEL_ID, "199904867"))
                .thenReturn(Optional.of(existing));
        when(blockRepo.findByChannelInRange(eq(CHANNEL_ID), any(), any()))
                .thenReturn(List.of(existing));

        ChannelSyncResult result = adapter.reconcile(ctx(),
                List.of(event("199904867", ECHO_FROM, ECHO_TO, false)));

        assertThat(result.removed()).isZero();
        verify(blockRepo, never()).delete(any());
        verify(echoRepo, never()).save(any());
    }
}
