package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Запланированная цена на дату для unit_type.
 * <p>
 * Запись существует только когда оператор явно задал цену через шахматку.
 * Отсутствие строки — не ошибка: цена вычисляется по unit_type.base_price /
 * weekend_price по правилам {@link ru.rentoptima.service.EffectivePriceService}.
 */
@Entity
@Table(name = "planned_prices")
@Getter
@Setter
@NoArgsConstructor
public class PlannedPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "unit_type_id", nullable = false)
    private Long unitTypeId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
