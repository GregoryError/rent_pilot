package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "properties")
@Getter
@Setter
@NoArgsConstructor
public class Property {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(length = 255)
    private String city;

    @Column(name = "rc_object_id", length = 100)
    private String rcObjectId;

    @Column(name = "feedback_code", nullable = false, unique = true)
    private String feedbackCode;

    @Column(name = "housekeeper_code", nullable = false, unique = true)
    private String housekeeperCode;

    @Column(name = "housekeeper_pin_hash")
    private String housekeeperPinHash;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
