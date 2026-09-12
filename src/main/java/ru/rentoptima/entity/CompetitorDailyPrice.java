package ru.rentoptima.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "competitor_daily_prices")
@Getter @Setter @NoArgsConstructor
public class CompetitorDailyPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "search_id")
    private Long searchId;

    @Column(name = "listing_id")
    private Long listingId;

    @Column(nullable = false, length = 100)
    private String platform;

    @Column(name = "competitor_name", length = 500)
    private String competitorName;

    @Column(name = "competitor_url", length = 2000)
    private String competitorUrl;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "min_stay")
    private Integer minStay;

    @Column(name = "scraped_at", nullable = false)
    private LocalDateTime scrapedAt = LocalDateTime.now();
}
