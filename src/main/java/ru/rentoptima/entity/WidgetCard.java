package ru.rentoptima.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;

/** Настройки печатной карточки для квартиры (V33). Одна на виджет. */
@Entity
@Table(name = "widget_cards")
@Getter
@Setter
@NoArgsConstructor
public class WidgetCard {

    public static final String FORMAT_CARD = "CARD";
    public static final String FORMAT_A6 = "A6";
    public static final String FORMAT_A5 = "A5";

    @Id
    @Column(name = "widget_id")
    private Long widgetId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    /** CARD — визитка 90×50 мм, A6 — открытка, A5 — листок. */
    @Column(nullable = false, length = 10)
    private String format = FORMAT_A6;

    /** Бумага в принтере: A4 (несколько карточек с метками реза) либо размер самой карточки. */
    @Column(nullable = false, length = 4)
    private String paper = "A4";

    @Column(name = "promo_code_id")
    private Long promoCodeId;

    /** Тексты, которые хозяин изменил; чего здесь нет — берётся из значений по умолчанию. */
    @Type(JsonBinaryType.class)
    @Column(name = "texts_json", columnDefinition = "jsonb")
    private JsonNode textsJson;

    @Column(name = "photo_id")
    private Long photoId;

    @Column(name = "wifi_ssid", length = 64)
    private String wifiSsid;

    /** Пароль Wi-Fi, зашифрованный EncryptionUtil. В публичный API не попадает. */
    @Column(name = "wifi_password_enc", columnDefinition = "TEXT")
    private String wifiPasswordEnc;

    @Column(name = "show_contacts", nullable = false)
    private Boolean showContacts = false;

    @Column(name = "show_feedback", nullable = false)
    private Boolean showFeedback = false;

    /** ru | en | both */
    @Column(nullable = false, length = 4)
    private String lang = "ru";

    /** Для чёрно-белого принтера: всё чёрное на белом. */
    @Column(nullable = false)
    private Boolean mono = false;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
