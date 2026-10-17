package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Алерт: одна проблема от появления до исчезновения.
 * <p>
 * Пока {@code resolvedAt == null}, алерт открыт и повторно не создаётся
 * (уникальный индекс на tenant + тип + dedup_key среди открытых — V20).
 * {@code notifiedAt == null} — сообщение в Telegram ещё не доставлено.
 */
@Entity
@Table(name = "alert_events")
@Getter
@Setter
@NoArgsConstructor
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 30)
    private AlertType alertType;

    /** Что считать «той же проблемой»: см. AlertSchedulerService. */
    @Column(name = "dedup_key", nullable = false, length = 200)
    private String dedupKey;

    @Column(name = "unit_type_id")
    private Long unitTypeId;

    @Column(name = "channel_id")
    private Long channelId;

    /** Для CONFLICT: первая конфликтная ночь. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    /** Для CONFLICT: день после последней конфликтной ночи (не включительно). */
    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt = LocalDateTime.now();

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public enum AlertType {
        CONFLICT,       // Ручная запись пересекается с занятостью с площадки
        CHANNEL_DOWN,   // Канал не синхронизируется несколько раз подряд
        OVERLAP         // Наложение без ручной записи (обычно эхо площадки): только в журнал
    }
}
