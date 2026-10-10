package ru.rentoptima.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Страница / виджет бронирования хоста (блок 4.9).
 * <p>
 * Один виджет = один канал типа WIDGET = одна категория номеров. Публичный API
 * виджета работает по {@code slug}; по секрету открываются прежние /book/{secret},
 * /widget/{secret} и вставка через /widget.js.
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

    /**
     * Публичный адрес: /b/{slug} и ключ API виджета. Латиница, цифры и дефис;
     * хозяин может поменять.
     */
    @Column(nullable = false, length = 80, unique = true)
    private String slug;

    /** 192 бита из SecureRandom, base64url. Ключ старых ссылок /book/ и /widget/. */
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

    /**
     * REQUEST — заявка, которую хозяин подтверждает вручную (даты держит резерв);
     * INSTANT — бронь создаётся сразу подтверждённой.
     */
    @Column(nullable = false, length = 20)
    private String mode = MODE_REQUEST;

    public static final String MODE_REQUEST = "REQUEST";
    public static final String MODE_INSTANT = "INSTANT";

    /** Сколько держать даты по заявке, пока хозяин не ответил. Сутки по умолчанию. */
    @Column(name = "hold_minutes", nullable = false)
    private Integer holdMinutes = 1440;

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

    /** Сбор за уборку, который платит гость. Не путать с настройкой cleaning_cost — расходом хозяина. */
    @Column(name = "cleaning_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal cleaningFee = BigDecimal.ZERO;

    /** Скидка при проживании от 7 ночей, %. 0 — нет. */
    @Column(name = "weekly_discount_percent", nullable = false)
    private Integer weeklyDiscountPercent = 0;

    /** Скидка при проживании от 28 ночей, %. 0 — нет. Со скидкой за 7 ночей не складывается. */
    @Column(name = "monthly_discount_percent", nullable = false)
    private Integer monthlyDiscountPercent = 0;

    /** Доля предоплаты, %. Пока только показывается гостю: оплата идёт мимо сервиса. */
    @Column(name = "prepayment_percent", nullable = false)
    private Integer prepaymentPercent = 0;

    @Column(name = "pets_allowed", nullable = false)
    private Boolean petsAllowed = false;

    /** Дни недели без заезда: номера ISO (1 — понедельник) через запятую. */
    @Column(name = "no_checkin_days", nullable = false, length = 20)
    private String noCheckinDays = "";

    /** Дни недели без выезда, в том же формате. */
    @Column(name = "no_checkout_days", nullable = false, length = 20)
    private String noCheckoutDays = "";

    /**
     * Сайты, на которые разрешено встраивать виджет: origin'ы вида
     * {@code https://example.ru}, по одному в строке. Пусто — только наш домен.
     */
    @Column(name = "allowed_origins", nullable = false, columnDefinition = "TEXT")
    private String allowedOrigins = "";

    /** Контакты хозяина для экрана подтверждения брони. */
    @Column(name = "contact_phone", length = 40)
    private String contactPhone;

    @Column(name = "contact_telegram", length = 80)
    private String contactTelegram;

    @Column(name = "contact_whatsapp", length = 40)
    private String contactWhatsapp;

    /** Раскладка и оформление виджета. Заполняется конструктором (следующие фазы). */
    @Type(JsonBinaryType.class)
    @Column(name = "config_json", columnDefinition = "jsonb")
    private JsonNode configJson;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
