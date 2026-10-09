package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Эхо ручной записи: событие {@code (channel_id, external_uid)} из фида канала —
 * это наша же ручная блокировка {@code manual_block_id}, которую площадка
 * импортировала из нашего экспорта и вернула обратно (см. V26).
 * <p>
 * Пока связь есть, синхронизация не создаёт по этому событию блокировку. Связи
 * удаляются каскадом вместе с ручной записью.
 */
@Entity
@Table(name = "manual_block_echoes")
@Getter
@Setter
@NoArgsConstructor
public class ManualBlockEcho {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "manual_block_id", nullable = false)
    private Long manualBlockId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "external_uid", nullable = false, length = 255)
    private String externalUid;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
