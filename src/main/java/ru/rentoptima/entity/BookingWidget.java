package ru.rentoptima.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Страница / виджет бронирования хоста (блок 4.9).
 * <p>
 * Один виджет = один канал типа WIDGET = одна категория номеров. По секрету
 * открываются /book/{secret}, /widget/{secret} и вставка через /widget.js.
 * Удаление мягкое ({@code active = false}): заявки и брони остаются в истории.
 */
@Entity
@Table(name = "booking_widgets")
@Getter
@Setter
@NoArgsConstructor
public class BookingWidget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "unit_type_id", nullable = false)
    private Long unitTypeId;

    /** 192 бита из SecureRandom, base64url. */
    @Column(nullable = false, length = 64, unique = true)
    private String secret;

    @Column(nullable = false)
    private String title;

    @Column(name = "min_nights", nullable = false)
    private Integer minNights = 1;

    @Column(name = "max_nights", nullable = false)
    private Integer maxNights = 30;

    @Column(name = "max_guests", nullable = false)
    private Integer maxGuests = 4;

    @Column(name = "booking_window_days", nullable = false)
    private Integer bookingWindowDays = 180;

    @Column(name = "checkin_time", nullable = false)
    private LocalTime checkinTime = LocalTime.of(14, 0);

    @Column(name = "checkout_time", nullable = false)
    private LocalTime checkoutTime = LocalTime.of(12, 0);

    /** В MVP только REQUEST: заявка, которую хост подтверждает вручную. */
    @Column(nullable = false, length = 20)
    private String mode = "REQUEST";

    @Column(name = "hold_hours", nullable = false)
    private Integer holdHours = 24;

    @Column(name = "show_price", nullable = false)
    private Boolean showPrice = true;

    @Column(name = "show_powered_by", nullable = false)
    private Boolean showPoweredBy = true;

    /** Зарезервировано: в MVP не редактируется и не выводится. */
    @Column(name = "custom_css", columnDefinition = "TEXT")
    private String customCss;

    /** light | dark | auto */
    @Column(nullable = false, length = 20)
    private String theme = "light";

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String rules;

    @Column(name = "cancellation_policy", columnDefinition = "TEXT")
    private String cancellationPolicy;

    @Column(name = "address_hint")
    private String addressHint;

    /** Массив URL фотографий. Пока хост даёт прямые ссылки, своего хранилища нет. */
    @Type(JsonBinaryType.class)
    @Column(name = "photos_json", columnDefinition = "jsonb")
    private JsonNode photosJson;

    /** Зарезервировано: в MVP не используется. */
    @Column(name = "show_host_contact", nullable = false)
    private Boolean showHostContact = false;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
