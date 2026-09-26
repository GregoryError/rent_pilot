package ru.rentoptima.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "manual_overrides")
@Getter
@Setter
@NoArgsConstructor
public class ManualOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "property_id")
    private Long propertyId;

    @Column(name = "override_type", nullable = false, length = 50)
    private String overrideType;

    @Type(JsonType.class)
    @Column(name = "params_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> params;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(nullable = false, length = 50)
    private String origin = "ai_chat";

    @Column(name = "created_by_message_id")
    private Long createdByMessageId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;
}
