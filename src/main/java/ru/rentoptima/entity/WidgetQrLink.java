package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Короткий адрес для QR-кода печатной карточки и для сообщения гостю (V33).
 * {@code /q/<code>} ведёт на страницу бронирования, {@code /f/<code>} — на форму
 * обратной связи. Перевыпущенная ссылка не удаляется, а помечается {@code revokedAt}:
 * напечатанные карточки должны открываться и дальше.
 */
@Entity
@Table(name = "widget_qr_links")
@Getter
@Setter
@NoArgsConstructor
public class WidgetQrLink {

    public static final String KIND_CARD = "CARD";
    public static final String KIND_MESSAGE = "MESSAGE";
    public static final String KIND_FEEDBACK = "FEEDBACK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "widget_id", nullable = false)
    private Long widgetId;

    @Column(nullable = false, length = 12, unique = true)
    private String code;

    /** CARD — QR на карточке; MESSAGE — ссылка в сообщении гостю; FEEDBACK — QR обратной связи. */
    @Column(nullable = false, length = 10)
    private String kind;

    /** Промокод, который подставит редирект; null — без промокода. */
    @Column(name = "promo_code_id")
    private Long promoCodeId;

    /** Ссылку перевыпустили: она ещё открывает страницу бронирования, но уже без промокода. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
