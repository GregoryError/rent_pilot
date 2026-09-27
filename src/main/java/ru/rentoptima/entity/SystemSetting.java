package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "system_settings")
@Getter @Setter @NoArgsConstructor
public class SystemSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false, insertable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "\"key\"", nullable = false)
    private String key;

    private String value;

    @Column(name = "is_encrypted", nullable = false)
    private Boolean encrypted = false;

    private String description;
}
