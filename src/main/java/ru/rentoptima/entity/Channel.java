package ru.rentoptima.entity;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;

/**
 * Подключённый канал синхронизации:
 * - RC: работа поверх RealtyCalendar (legacy path, wrapper via RcChannelAdapter)
 * - AVITO: официальный API Авито
 * - ICAL: iCal-подключение к любой площадке (Sutochno, Ostrovok, Booking, etc.)
 * - MANUAL: ручные брони (не тянутся ни откуда, но экспортируются в iCal)
 * - WIDGET: прямые брони через страницу/виджет бронирования (см. BookingWidget)
 * <p>
 * config_json содержит специфичные для канала параметры:
 * - RC: null (rc_object_id хранится в properties)
 * - AVITO: { "item_id": "...", "oauth_client_id": "..." }
 * - ICAL: { "import_url": "https://..." }
 * - MANUAL: {}
 * <p>
 * export_secret — публичный секретный токен для отдачи iCal-фида этого канала
 * (используется только для каналов с supportsIcalExport = true).
 */
@Entity
@Table(name = "channels")
@Getter
@Setter
@NoArgsConstructor
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "unit_type_id", nullable = false)
    private Long unitTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false, length = 20)
    private ChannelType channelType;

    @Column(nullable = false, length = 200)
    private String name;

    @Type(JsonBinaryType.class)
    @Column(name = "config_json", columnDefinition = "jsonb")
    private JsonNode configJson;

    @Column(name = "sync_enabled", nullable = false)
    private Boolean syncEnabled = true;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "last_sync_imported")
    private Integer lastSyncImported;

    @Column(name = "last_sync_removed")
    private Integer lastSyncRemoved;

    /** Сколько синхронизаций подряд упало; сбрасывается первой успешной. Для алертов. */
    @Column(name = "consecutive_errors", nullable = false)
    private Integer consecutiveErrors = 0;

    /**
     * Публичный секрет для iCal-экспорта: /ical/{export_secret}.ics
     * 192 бита энтропии, base64-url-encoded (~32 символа).
     */
    @Column(name = "export_secret", length = 64, unique = true)
    private String exportSecret;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public enum ChannelType {
        RC,
        AVITO,
        ICAL,
        MANUAL,
        WIDGET
    }
}
