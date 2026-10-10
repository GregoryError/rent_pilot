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

    // --- Брони с виджета (V29). У остальных броней поля пустые.

    private Integer adults;

    private Integer children;

    private Integer pets;

    /** Сохраняется только с согласием гостя — на него уходит письмо о брони. */
    @Column(name = "guest_email")
    private String guestEmail;

    /** Язык, на котором гость оформлял бронь: ru | en. */
    @Column(length = 5)
    private String locale;

    /** Номер брони, который видит гость. */
    @Column(name = "public_code", length = 12)
    private String publicCode;

    /** Сбор за уборку, вошедший в amount. */
    @Column(name = "cleaning_fee")
    private BigDecimal cleaningFee;

    /** Сумма скидок (за длительность и по промокоду), уже вычтенная из amount. */
    @Column(name = "discount_amount")
    private BigDecimal discountAmount;

    @Column(name = "promo_code_id")
    private Long promoCodeId;

    /** Предоплата, о которой сообщили гостю. Сервис её не принимает. */
    @Column(name = "prepayment_amount")
    private BigDecimal prepaymentAmount;

    @Column(name = "utm_source", length = 100)
    private String utmSource;

    @Column(name = "utm_medium", length = 100)
    private String utmMedium;

    @Column(name = "utm_campaign", length = 100)
    private String utmCampaign;

    @Column(length = 500)
    private String referrer;

    /** Когда гость отметил согласие на обработку контактных данных. */
    @Column(name = "consent_at")
    private LocalDateTime consentAt;

    /** Хозяину уже напомнили, что заявка скоро истечёт. */
    @Column(name = "hold_reminder_sent_at")
    private LocalDateTime holdReminderSentAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
