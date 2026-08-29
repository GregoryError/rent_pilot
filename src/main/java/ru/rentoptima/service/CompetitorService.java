package ru.rentoptima.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.CompetitorListing;
import ru.rentoptima.entity.CompetitorPrice;
import ru.rentoptima.repository.CompetitorListingRepository;
import ru.rentoptima.repository.CompetitorPriceRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompetitorService {

    private final CompetitorListingRepository listingRepo;
    private final CompetitorPriceRepository priceRepo;
    private final SettingsService settings;

    private static final Pattern PRICE_PATTERN = Pattern.compile("([\\d\\s]+)\\s*₽");

    public List<CompetitorListing> getListings(Long tenantId) {
        return listingRepo.findByTenantIdAndActiveTrue(tenantId);
    }

    @Transactional
    public CompetitorListing addListing(Long tenantId, Long propertyId, String name, String url, String platform) {
        CompetitorListing listing = new CompetitorListing();
        listing.setTenantId(tenantId);
        listing.setPropertyId(propertyId);
        listing.setCompetitorName(name);
        listing.setUrl(url);
        listing.setPlatform(detectPlatform(url, platform));
        listing.setActive(true);
        return listingRepo.save(listing);
    }

    @Transactional
    public void deleteListing(Long id) {
        listingRepo.findById(id).ifPresent(l -> {
            l.setActive(false);
            listingRepo.save(l);
        });
    }

    /** Scrape all active listings — every 12 hours */
    @Scheduled(fixedDelay = 43200000)
    public void scrapeAll() {
        List<CompetitorListing> listings = listingRepo.findAllActive();
        if (listings.isEmpty()) return;

        log.info("Scraping {} competitor listings", listings.size());
        for (CompetitorListing listing : listings) {
            try {
                scrapeOne(listing);
            } catch (Exception e) {
                log.warn("Failed to scrape {}: {}", listing.getUrl(), e.getMessage());
            }
        }
    }

    @Transactional
    public ScrapeResult scrapeOne(CompetitorListing listing) {
        try {
            Document doc = Jsoup.connect(listing.getUrl())
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(15_000)
                    .get();

            BigDecimal price = extractPrice(doc, listing.getPlatform());
            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                CompetitorPrice cp = new CompetitorPrice();
                cp.setListingId(listing.getId());
                cp.setPrice(price);
                cp.setScrapedAt(LocalDateTime.now());
                priceRepo.save(cp);

                listing.setLastScrapedAt(LocalDateTime.now());
                listingRepo.save(listing);

                log.info("Scraped {}: {} ₽", listing.getCompetitorName(), price);
                return new ScrapeResult(true, price, null);
            } else {
                return new ScrapeResult(false, null, "Цена не найдена на странице");
            }
        } catch (Exception e) {
            log.warn("Scrape error for {}: {}", listing.getCompetitorName(), e.getMessage());
            return new ScrapeResult(false, null, e.getMessage());
        }
    }

    private BigDecimal extractPrice(Document doc, String platform) {
        if ("avito".equalsIgnoreCase(platform)) {
            return extractAvito(doc);
        } else if ("cian".equalsIgnoreCase(platform)) {
            return extractCian(doc);
        }
        // Generic: look for price patterns
        return extractGeneric(doc);
    }

    private BigDecimal extractAvito(Document doc) {
        // Avito price selectors (may change)
        Elements priceEls = doc.select("[data-marker='item-view/item-price'] span, .js-item-price, .item-price-text");
        for (Element el : priceEls) {
            BigDecimal p = parsePrice(el.text());
            if (p != null) return p;
        }
        // Fallback: itemprop price
        Element metaPrice = doc.selectFirst("[itemprop=price]");
        if (metaPrice != null) {
            String val = metaPrice.attr("content");
            try { return new BigDecimal(val); } catch (Exception ignored) {}
        }
        return extractGeneric(doc);
    }

    private BigDecimal extractCian(Document doc) {
        Elements priceEls = doc.select("[data-name='PriceInfo'] span, .a10a3f92e9--price--");
        for (Element el : priceEls) {
            BigDecimal p = parsePrice(el.text());
            if (p != null) return p;
        }
        return extractGeneric(doc);
    }

    private BigDecimal extractGeneric(Document doc) {
        String text = doc.text();
        Matcher m = PRICE_PATTERN.matcher(text);
        while (m.find()) {
            BigDecimal p = parsePrice(m.group(1) + " ₽");
            if (p != null && p.compareTo(BigDecimal.valueOf(500)) > 0
                    && p.compareTo(BigDecimal.valueOf(50000)) < 0) {
                return p;
            }
        }
        return null;
    }

    private BigDecimal parsePrice(String text) {
        if (text == null) return null;
        String cleaned = text.replaceAll("[^\\d]", "");
        if (cleaned.isEmpty()) return null;
        try {
            BigDecimal val = new BigDecimal(cleaned);
            if (val.compareTo(BigDecimal.valueOf(100)) > 0) return val;
        } catch (Exception ignored) {}
        return null;
    }

    private String detectPlatform(String url, String platform) {
        if (platform != null && !platform.isBlank()) return platform;
        if (url.contains("avito")) return "avito";
        if (url.contains("cian")) return "cian";
        if (url.contains("sutochno")) return "sutochno";
        if (url.contains("ostrovok")) return "ostrovok";
        return "other";
    }

    /** Get latest prices for all competitors of a tenant */
    public List<CompetitorPriceView> getLatestPrices(Long tenantId) {
        return priceRepo.findLatestByTenant(tenantId);
    }

    /** Average competitor price */
    public BigDecimal getAverageCompetitorPrice(Long tenantId) {
        return priceRepo.avgLatestPrice(tenantId);
    }

    public record ScrapeResult(boolean success, BigDecimal price, String error) {}
    public record CompetitorPriceView(String name, String platform, BigDecimal price, LocalDateTime scrapedAt) {}
}
