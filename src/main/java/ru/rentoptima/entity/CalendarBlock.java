package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Блокировка дат: ручная бронь, ремонт, личное использование, hold, iCal-импорт.
 * Отличается от Booking тем, что не имеет цены/гостя/деталей — это просто
 * пометка "unit type занят в эти даты".
 * <p>
 * Экспортируется во все iCal-каналы этого unit_type для защиты от овербукинга,
 * с исключением самого канала-источника (anti-echo).
 * <p>
 * Ключ идемпотентности для iCal-импорта: (channel_id, external_uid).
 * Уникальный индекс на паре — в V17.
 */
@Entity
@Table(name = "calendar_blocks")
@Getter
@Setter
@NoArgsConstructor
public class CalendarBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "unit_type_id", nullable = false)
    private Long unitTypeId;

    /**
     * Канал-источник блокировки:
     * - null для ручных броней и MAINTENANCE/OWNER_USE/HOLD (заводит человек в UI)
     * - id iCal-канала для CHANNEL_SYNC (импорт с площадки)
     */
    @Column(name = "channel_id")
    private Long channelId;

    /**
     * UID из внешнего iCal (VEVENT.UID) — ключ идемпотентности при повторных импортах.
     * null для ручных блокировок.
     */
    @Column(name = "external_uid", length = 255)
    private String externalUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_type", nullable = false, length = 20)
    private BlockType blockType;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    private String reason;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public enum BlockType {
        MANUAL_BOOKING,   // Ручная бронь (например через звонок)
        MAINTENANCE,      // Ремонт, уборка после ЧП
        OWNER_USE,        // Хозяин заехал сам
        HOLD,             // Резерв (например ожидание оплаты)
        CHANNEL_SYNC      // Импортировано из iCal внешнего канала
    }
}
