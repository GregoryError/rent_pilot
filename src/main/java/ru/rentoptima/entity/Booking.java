package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "bookings")
@Getter @Setter @NoArgsConstructor
public class Booking extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    private String source;

    @Column(nullable = false)
    private String status = "BOOKED";

    @Column(name = "guest_name")
    private String guestName;

    @Column(name = "guest_phone")
    private String guestPhone;

    @Column(name = "guest_count")
    private Integer guestCount;

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    @Column(nullable = false)
    private Integer nights;

    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal commission = BigDecimal.ZERO;

    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "amount_refunded", nullable = false)
    private BigDecimal amountRefunded = BigDecimal.ZERO;

    private String notes;

    @Column(name = "manager_name")
    private String managerName;

    /** ID канала откуда пришла бронь. Nullable для legacy бронирований до V16. */
    @Column(name = "channel_id")
    private Long channelId;

    /** ID unit_type — заполняется автоматом при импорте, для legacy заполнен по property. */
    @Column(name = "unit_type_id")
    private Long unitTypeId;

    /**
     * External ID брони в исходной системе:
     * - Avito: booking_id из Avito API
     * - iCal: UID из VEVENT
     * - Manual: null
     */
    @Column(name = "external_id")
    private String externalId;

    /**
     * Классификатор источника данных брони для дашборда и моделирования выручки.
     * 'ICAL' | 'AVITO' | 'MANUAL' | 'WIDGET' (заявка со страницы бронирования) |
     * {@link #DATA_SOURCE_LEGACY} — исторические брони, загруженные до перехода на iCal-каналы.
     * <p>
     * Используется для различения «реальная сумма» (исторические, AVITO) и «моделируем
     * по запланированной цене» (ICAL, MANUAL без указанной суммы).
     */
    @Column(name = "data_source", length = 30)
    private String dataSource;

    /**
     * Значение data_source у исторических броней, загруженных до перехода на
     * iCal-каналы. Новых таких записей не появляется; значение в базе не меняем.
     */
    public static final String DATA_SOURCE_LEGACY = "RC";

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
