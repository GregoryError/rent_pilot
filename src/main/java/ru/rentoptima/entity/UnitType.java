package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Категория номеров внутри property.
 * Для квартиры обычно один unit_type с unit_count=1 (дефолтный "Основной").
 * Для мини-отеля — несколько: "Стандарт" (count=8), "Люкс" (count=3), etc.
 */
@Entity
@Table(name = "unit_types")
@Getter
@Setter
@NoArgsConstructor
public class UnitType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "unit_count", nullable = false)
    private Integer unitCount = 1;

    @Column(name = "base_price", precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "weekend_price", precision = 10, scale = 2)
    private BigDecimal weekendPrice;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
