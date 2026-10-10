package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Промокод хозяина для страницы бронирования (V29). Действует в пределах одного виджета. */
@Entity
@Table(name = "promo_codes")
@Getter
@Setter
@NoArgsConstructor
public class PromoCode {

    public static final String TYPE_PERCENT = "PERCENT";
    public static final String TYPE_AMOUNT = "AMOUNT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "widget_id", nullable = false)
    private Long widgetId;

    /** В верхнем регистре, без пробелов. */
    @Column(nullable = false, length = 40)
    private String code;

    /** PERCENT — процент от стоимости проживания, AMOUNT — сумма в рублях. */
    @Column(name = "discount_type", nullable = false, length = 10)
    private String discountType;

    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    /** Последний день действия, включительно. */
    @Column(name = "valid_until")
    private LocalDate validUntil;

    /** null — без ограничения. */
    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Приводит введённый код к виду, в котором он хранится. null — код пустой. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String code = raw.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        return code.isEmpty() ? null : code;
    }
}
