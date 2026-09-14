package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "pricing_insights")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricingInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "property_id")
    private Long propertyId;

    @Column(name = "pattern_key", nullable = false, length = 80)
    private String patternKey;

    @Column(name = "condition_json", columnDefinition = "jsonb")
    private String conditionJson;

    @Column(name = "action_json", columnDefinition = "jsonb")
    private String actionJson;

    @Column(name = "sample_size", nullable = false)
    private Integer sampleSize = 0;

    @Column(name = "conversion_rate")
    private Double conversionRate;

    @Column(name = "avg_days_to_booking")
    private Double avgDaysToBooking;

    private Double confidence;

    @Column(name = "summary_text", columnDefinition = "text")
    private String summaryText;

    @Column(name = "discovered_at", nullable = false)
    private LocalDateTime discoveredAt;

    @Column(name = "last_confirmed_at")
    private LocalDateTime lastConfirmedAt;
}
