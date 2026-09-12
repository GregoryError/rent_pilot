package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "competitor_searches")
@Getter @Setter @NoArgsConstructor
public class CompetitorSearch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "property_id")
    private Long propertyId;

    @Column(nullable = false, length = 100)
    private String platform;

    @Column(name = "search_url", nullable = false, length = 2000)
    private String searchUrl;

    @Column(name = "search_name")
    private String searchName;

    private String city;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "last_scraped_at")
    private LocalDateTime lastScrapedAt;

    @Column(name = "scrape_interval_hours", nullable = false)
    private Integer scrapeIntervalHours = 12;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
