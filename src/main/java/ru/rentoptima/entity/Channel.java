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
 * - RC: работа поверх RealtyCalendar (legacy path)
 * - AVITO: официальный API Авито
 * - ICAL: iCal-подключение к любой площадке (Sutochno, Ostrovok, Booking, etc.)
 * - MANUAL: ручные брони (не тянутся ни откуда, но экспортируются в iCal)
 *
 * config_json содержит специфичные для канала параметры:
 * - RC: { "rc_object_id": "211995" }
 * - AVITO: { "item_id": "...", "oauth_client_id": "..." }
 * - ICAL: { "import_url": "https://...", "export_secret": "abc123" }
 * - MANUAL: {}
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
        MANUAL
    }
}
