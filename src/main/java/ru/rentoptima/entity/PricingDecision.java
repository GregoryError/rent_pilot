package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "pricing_decisions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricingDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;

    @Column(nullable = false)
    private Integer price;

    @Column(name = "min_stay", nullable = false)
    private Integer minStay;

    @Column(name = "ai_multiplier")
    private Double aiMultiplier;

    @Column(name = "days_ahead", nullable = false)
    private Integer daysAhead;

    @Column(name = "window_len")
    private Integer windowLen;

    @Column(name = "is_weekend", nullable = false)
    private Boolean isWeekend = false;

    @Column(name = "is_holiday", nullable = false)
    private Boolean isHoliday = false;

    @Column(name = "booking_pace")
    private Double bookingPace;

    @Column(name = "competitor_avg_price")
    private Integer competitorAvgPrice;

    @Column(name = "booked_at")
    private LocalDateTime bookedAt;

    @Column(name = "booked_amount")
    private BigDecimal bookedAmount;

    @Column(name = "days_to_booking")
    private Integer daysToBooking;

    @Column(length = 20)
    private String outcome;
}
