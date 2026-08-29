package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "competitor_listings")
@Getter @Setter @NoArgsConstructor
public class CompetitorListing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "property_id")
    private Long propertyId;

    @Column(name = "competitor_name", nullable = false)
    private String competitorName;

    @Column(nullable = false, length = 1000)
    private String url;

    private String platform;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "last_scraped_at")
    private LocalDateTime lastScrapedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
