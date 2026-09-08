package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import ru.rentoptima.entity.CompetitorListing;
import ru.rentoptima.entity.CompetitorPrice;
import ru.rentoptima.repository.CompetitorListingRepository;
import ru.rentoptima.repository.CompetitorPriceRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompetitorService {

    private final CompetitorListingRepository listingRepo;
    private final CompetitorPriceRepository priceRepo;
    private final SettingsService settings;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String API_URL = "https://api.anthropic.com/v1/messages";

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

    /** Weekly auto-scrape: every 3.5 days (twice a week) */
    @Scheduled(fixedDelay = 302400000)
    public void scrapeAll() {
        List<CompetitorListing> listings = listingRepo.findAllActive();
        if (listings.isEmpty()) return;

        // Need API key from any tenant that has one
        String apiKey = null;
        for (CompetitorListing l : listings) {
            apiKey = settings.getValue(l.getTenantId(), "anthropic_api_key");
            if (apiKey != null && !apiKey.isBlank()) break;
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("No Anthropic API key configured, skipping competitor scrape");
            return;
        }

        log.info("Scraping {} competitor listings via AI", listings.size());
        for (CompetitorListing listing : listings) {
            try {
                scrapeOne(listing);
                Thread.sleep(2000); // Rate limit between requests
            } catch (Exception e) {
                log.warn("Failed to scrape {}: {}", listing.getCompetitorName(), e.getMessage());
            }
        }
    }

    @Transactional
    public ScrapeResult scrapeOne(CompetitorListing listing) {
        String apiKey = settings.getValue(listing.getTenantId(), "anthropic_api_key");
        if (apiKey == null || apiKey.isBlank()) {
            return new ScrapeResult(false, null, "API-ключ не настроен");
        }

        try {
            // Step 1: fetch page HTML
            String html = fetchPageHtml(listing.getUrl());
            if (html == null || html.length() < 100) {
                return new ScrapeResult(false, null, "Не удалось загрузить страницу");
            }

            // Truncate HTML to avoid token overflow (keep relevant parts)
            String truncatedHtml = truncateHtml(html, 8000);

            // Step 2: ask Claude to extract price
            BigDecimal price = extractPriceViaAi(apiKey, truncatedHtml, listing.getUrl(), listing.getPlatform());

            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                CompetitorPrice cp = new CompetitorPrice();
                cp.setListingId(listing.getId());
                cp.setPrice(price);
                cp.setScrapedAt(LocalDateTime.now());
                priceRepo.save(cp);

                listing.setLastScrapedAt(LocalDateTime.now());
                listingRepo.save(listing);

                log.info("AI extracted price for {}: {} ₽", listing.getCompetitorName(), price);
                return new ScrapeResult(true, price, null);
            }
            return new ScrapeResult(false, null, "AI не смог определить цену");

        } catch (Exception e) {
            log.warn("Scrape error for {}: {}", listing.getCompetitorName(), e.getMessage());
            return new ScrapeResult(false, null, e.getMessage());
        }
    }

    private String fetchPageHtml(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(15_000)
                    .followRedirects(true)
                    .get();
            return doc.html();
        } catch (Exception e) {
            log.warn("Failed to fetch {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String truncateHtml(String html, int maxChars) {
        // Remove scripts, styles, SVGs to save tokens
        String cleaned = html
                .replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                .replaceAll("<svg[^>]*>[\\s\\S]*?</svg>", "")
                .replaceAll("<noscript[^>]*>[\\s\\S]*?</noscript>", "")
                .replaceAll("<!--[\\s\\S]*?-->", "")
                .replaceAll("\\s+", " ");
        if (cleaned.length() > maxChars) {
            cleaned = cleaned.substring(0, maxChars);
        }
        return cleaned;
    }

    private BigDecimal extractPriceViaAi(String apiKey, String html, String url, String platform) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", "claude-sonnet-4-6");
            root.put("max_tokens", 200);
            root.put("system", """
                    Ты извлекаешь цену посуточной аренды квартиры из HTML-страницы объявления.
                    Ответь ТОЛЬКО числом — цена за сутки в рублях. Ничего больше.
                    Если цена указана как диапазон, верни минимальную.
                    Если на странице несколько цен, верни цену за сутки (не за месяц, не залог).
                    Если цену определить невозможно, ответь: 0
                    """);

            ArrayNode messages = root.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", String.format(
                    "Извлеки цену за сутки из этого объявления (%s, %s):\n\n%s",
                    platform, url, html
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(root), headers);
            ResponseEntity<JsonNode> response = restTemplate.exchange(API_URL, HttpMethod.POST, entity, JsonNode.class);

            if (response.getBody() != null && response.getBody().has("content")) {
                String text = response.getBody().get("content").get(0).path("text").asText().trim();
                // Extract number from response
                String digits = text.replaceAll("[^\\d]", "");
                if (!digits.isEmpty()) {
                    BigDecimal price = new BigDecimal(digits);
                    // Sanity check: reasonable price range for daily rental
                    if (price.compareTo(BigDecimal.valueOf(500)) >= 0
                            && price.compareTo(BigDecimal.valueOf(50000)) <= 0) {
                        return price;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("AI price extraction failed: {}", e.getMessage());
        }
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

    public List<CompetitorPriceView> getLatestPrices(Long tenantId) {
        return priceRepo.findLatestByTenant(tenantId);
    }

    public BigDecimal getAverageCompetitorPrice(Long tenantId) {
        return priceRepo.avgLatestPrice(tenantId);
    }

    public record ScrapeResult(boolean success, BigDecimal price, String error) {}
    public record CompetitorPriceView(String name, String platform, BigDecimal price, LocalDateTime scrapedAt) {}
}
