package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "properties")
@Getter @Setter @NoArgsConstructor
public class Property extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private String name;

    private String address;
    private String city;

    @Column(name = "rc_object_id")
    private String rcObjectId;

    @Column(name = "feedback_code", nullable = false, unique = true)
    private String feedbackCode;

    @Column(name = "housekeeper_code", nullable = false, unique = true)
    private String housekeeperCode;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
