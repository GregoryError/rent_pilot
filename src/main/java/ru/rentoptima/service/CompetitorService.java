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
import ru.rentoptima.entity.CompetitorDailyPrice;
import ru.rentoptima.entity.CompetitorListing;
import ru.rentoptima.entity.CompetitorPrice;
import ru.rentoptima.entity.CompetitorSearch;
import ru.rentoptima.repository.CompetitorDailyPriceRepository;
import ru.rentoptima.repository.CompetitorListingRepository;
import ru.rentoptima.repository.CompetitorPriceRepository;
import ru.rentoptima.repository.CompetitorSearchRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Сервис анализа конкурентных цен.
 *
 * Поддерживаемые платформы для поискового скрапинга (с реальными ценами по датам):
 * - Sutochno.ru — цены в выдаче зависят от checkin/checkout
 * - Ostrovok.ru — цены в выдаче зависят от дат поиска
 *
 * Avito НЕ поддерживается для поискового скрапинга:
 * цены в выдаче Avito — это базовая цена объявления ("от X₽/сут"),
 * она не меняется от дат. Реальные цены по датам доступны только
 * в JS-календаре на странице объявления, который Jsoup не рендерит.
 * Avito-объявления можно добавлять через legacy-подход (competitor_listings),
 * но извлечённая цена будет базовой, без привязки к конкретной дате.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompetitorService {

    private final CompetitorListingRepository listingRepo;
    private final CompetitorPriceRepository priceRepo;
    private final CompetitorSearchRepository searchRepo;
    private final CompetitorDailyPriceRepository dailyPriceRepo;
    private final SettingsService settings;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String API_URL = "https://api.anthropic.com/v1/messages";

    /** Платформы, поддерживающие реальные цены по датам в поисковой выдаче. */
    private static final Set<String> SUPPORTED_SEARCH_PLATFORMS = Set.of("sutochno", "ostrovok");

    private static final String[] USER_AGENTS = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    };

    // ==================== Listings (legacy single-page approach) ====================

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

    // ==================== Search-based approach (Sutochno, Ostrovok) ====================

    public List<CompetitorSearch> getSearches(Long tenantId) {
        return searchRepo.findByTenantIdAndActiveTrue(tenantId);
    }

    @Transactional
    public CompetitorSearch addSearch(Long tenantId, Long propertyId,
                                     String platform, String searchUrl,
                                     String name, String city) {
        String normalizedPlatform = platform.toLowerCase();

        if (!SUPPORTED_SEARCH_PLATFORMS.contains(normalizedPlatform)) {
            throw new IllegalArgumentException(
                    "Платформа '" + platform + "' не поддерживается для поискового скрапинга. "
                    + "Поддерживаются: " + SUPPORTED_SEARCH_PLATFORMS + ". "
                    + "Для Avito используйте добавление конкретных объявлений (legacy).");
        }

        CompetitorSearch search = new CompetitorSearch();
        search.setTenantId(tenantId);
        search.setPropertyId(propertyId);
        search.setPlatform(normalizedPlatform);
        search.setSearchUrl(searchUrl);
        search.setSearchName(name != null ? name : platform + " поиск");
        search.setCity(city);
        search.setActive(true);
        return searchRepo.save(search);
    }

    @Transactional
    public void deleteSearch(Long id) {
        searchRepo.findById(id).ifPresent(s -> {
            s.setActive(false);
            searchRepo.save(s);
        });
    }

    // ==================== Scheduled scraping ====================

    /**
     * Main scheduled scrape: runs every 4 hours.
     * Search-based scraping only for Sutochno/Ostrovok (real per-date prices).
     * Legacy listings still scraped for backward compatibility.
     */
    @Scheduled(fixedDelay = 14400000) // 4 hours
    public void scrapeAll() {
        scrapeSearchBased();
        scrapeLegacyListings();
    }

    /**
     * Search-based scraping: fetch search results pages from Sutochno/Ostrovok
     * and extract multiple competitor prices per date via AI.
     *
     * Only platforms in SUPPORTED_SEARCH_PLATFORMS are scraped — their search
     * results show real prices for the requested dates, not static base prices.
     */
    private void scrapeSearchBased() {
        List<CompetitorSearch> searches = searchRepo.findDueForScraping();

        // Filter to supported platforms only
        searches = searches.stream()
                .filter(s -> SUPPORTED_SEARCH_PLATFORMS.contains(s.getPlatform().toLowerCase()))
                .toList();

        if (searches.isEmpty()) return;

        String apiKey = findApiKey(searches.stream()
                .map(CompetitorSearch::getTenantId).distinct().toList());
        if (apiKey == null) return;

        log.info("Scraping {} competitor searches (Sutochno/Ostrovok) via AI", searches.size());

        for (CompetitorSearch search : searches) {
            try {
                scrapeSearch(search, apiKey);
                long delay = 4000 + ThreadLocalRandom.current().nextLong(4000);
                Thread.sleep(delay);
            } catch (Exception e) {
                log.warn("Failed to scrape search {}: {}", search.getSearchName(), e.getMessage());
            }
        }
    }

    /**
     * Legacy listing scrape for individual pages (kept for backward compatibility).
     * Works with any platform including Avito, but extracts only the base price.
     */
    private void scrapeLegacyListings() {
        List<CompetitorListing> listings = listingRepo.findAllActive();
        if (listings.isEmpty()) return;

        String apiKey = findApiKey(listings.stream()
                .map(CompetitorListing::getTenantId).distinct().toList());
        if (apiKey == null) return;

        log.info("Scraping {} legacy competitor listings via AI", listings.size());
        for (CompetitorListing listing : listings) {
            try {
                scrapeOne(listing);
                long delay = 4000 + ThreadLocalRandom.current().nextLong(6000);
                Thread.sleep(delay);
            } catch (Exception e) {
                log.warn("Failed to scrape {}: {}", listing.getCompetitorName(), e.getMessage());
            }
        }
    }

    // ==================== Search scraping logic ====================

    @Transactional
    public SearchScrapeResult scrapeSearch(CompetitorSearch search, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = settings.getValue(search.getTenantId(), "anthropic_api_key");
        }
        if (apiKey == null || apiKey.isBlank()) {
            return new SearchScrapeResult(false, 0, "API-ключ не настроен");
        }

        String platform = search.getPlatform().toLowerCase();
        if (!SUPPORTED_SEARCH_PLATFORMS.contains(platform)) {
            log.warn("Skipping search scrape for unsupported platform: {}", platform);
            return new SearchScrapeResult(false, 0,
                    "Платформа " + platform + " не поддерживает цены по датам в выдаче");
        }

        try {
            LocalDate checkIn = LocalDate.now().plusDays(1);
            LocalDate checkOut = checkIn.plusDays(1);
            String url = buildSearchUrl(search, checkIn, checkOut);

            String html = fetchPageHtml(url);
            if (html == null || html.length() < 200) {
                return new SearchScrapeResult(false, 0, "Не удалось загрузить поисковую выдачу");
            }

            String truncatedHtml = truncateHtml(html, 12000);

            List<ExtractedCompetitorPrice> prices =
                    extractSearchPricesViaAi(apiKey, truncatedHtml, search.getPlatform(),
                            search.getCity(), checkIn);

            int saved = 0;
            LocalDateTime now = LocalDateTime.now();
            for (ExtractedCompetitorPrice ep : prices) {
                CompetitorDailyPrice dp = new CompetitorDailyPrice();
                dp.setTenantId(search.getTenantId());
                dp.setSearchId(search.getId());
                dp.setPlatform(search.getPlatform());
                dp.setCompetitorName(ep.name());
                dp.setCompetitorUrl(ep.url());
                dp.setDate(ep.date());
                dp.setPrice(ep.price());
                dp.setMinStay(ep.minStay());
                dp.setScrapedAt(now);
                dailyPriceRepo.save(dp);
                saved++;
            }

            saved += scrapeMultipleDates(search, apiKey, now);

            search.setLastScrapedAt(now);
            searchRepo.save(search);

            log.info("Search scrape [{}]: extracted {} prices for {}",
                    search.getPlatform(), saved, search.getSearchName());
            return new SearchScrapeResult(true, saved, null);

        } catch (Exception e) {
            log.warn("Search scrape error for {}: {}", search.getSearchName(), e.getMessage());
            return new SearchScrapeResult(false, 0, e.getMessage());
        }
    }

    /**
     * Scrapes prices for multiple future dates to build a competitor calendar.
     * Checks dates: +3, +7, +14, +21, +30, +45 days ahead.
     */
    private int scrapeMultipleDates(CompetitorSearch search, String apiKey, LocalDateTime now) {
        int[] daysAhead = {3, 7, 14, 21, 30, 45};
        int totalSaved = 0;

        for (int days : daysAhead) {
            try {
                LocalDate checkIn = LocalDate.now().plusDays(days);
                LocalDate checkOut = checkIn.plusDays(1);
                String url = buildSearchUrl(search, checkIn, checkOut);

                long delay = 3000 + ThreadLocalRandom.current().nextLong(5000);
                Thread.sleep(delay);

                String html = fetchPageHtml(url);
                if (html == null || html.length() < 200) continue;

                String truncatedHtml = truncateHtml(html, 12000);
                List<ExtractedCompetitorPrice> prices =
                        extractSearchPricesViaAi(apiKey, truncatedHtml, search.getPlatform(),
                                search.getCity(), checkIn);

                for (ExtractedCompetitorPrice ep : prices) {
                    CompetitorDailyPrice dp = new CompetitorDailyPrice();
                    dp.setTenantId(search.getTenantId());
                    dp.setSearchId(search.getId());
                    dp.setPlatform(search.getPlatform());
                    dp.setCompetitorName(ep.name());
                    dp.setCompetitorUrl(ep.url());
                    dp.setDate(ep.date());
                    dp.setPrice(ep.price());
                    dp.setMinStay(ep.minStay());
                    dp.setScrapedAt(now);
                    dailyPriceRepo.save(dp);
                    totalSaved++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Multi-date scrape failed for +{}d: {}", days, e.getMessage());
            }
        }
        return totalSaved;
    }

    // ==================== Legacy single listing scrape ====================

    @Transactional
    public ScrapeResult scrapeOne(CompetitorListing listing) {
        String apiKey = settings.getValue(listing.getTenantId(), "anthropic_api_key");
        if (apiKey == null || apiKey.isBlank()) {
            return new ScrapeResult(false, null, "API-ключ не настроен");
        }

        try {
            String html = fetchPageHtml(listing.getUrl());
            if (html == null || html.length() < 100) {
                return new ScrapeResult(false, null, "Не удалось загрузить страницу");
            }

            String truncatedHtml = truncateHtml(html, 8000);
            BigDecimal price = extractPriceViaAi(apiKey, truncatedHtml,
                    listing.getUrl(), listing.getPlatform());

            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                CompetitorPrice cp = new CompetitorPrice();
                cp.setListingId(listing.getId());
                cp.setPrice(price);
                cp.setScrapedAt(LocalDateTime.now());
                priceRepo.save(cp);

                CompetitorDailyPrice dp = new CompetitorDailyPrice();
                dp.setTenantId(listing.getTenantId());
                dp.setListingId(listing.getId());
                dp.setPlatform(listing.getPlatform());
                dp.setCompetitorName(listing.getCompetitorName());
                dp.setCompetitorUrl(listing.getUrl());
                dp.setDate(LocalDate.now());
                dp.setPrice(price);
                dp.setScrapedAt(LocalDateTime.now());
                dailyPriceRepo.save(dp);

                listing.setLastScrapedAt(LocalDateTime.now());
                listingRepo.save(listing);

                log.info("AI extracted price for {}: {} rub",
                        listing.getCompetitorName(), price);
                return new ScrapeResult(true, price, null);
            }
            return new ScrapeResult(false, null, "AI не смог определить цену");

        } catch (Exception e) {
            log.warn("Scrape error for {}: {}",
                    listing.getCompetitorName(), e.getMessage());
            return new ScrapeResult(false, null, e.getMessage());
        }
    }

    // ==================== URL building (Sutochno + Ostrovok only) ====================

    private String buildSearchUrl(CompetitorSearch search,
                                  LocalDate checkIn, LocalDate checkOut) {
        String baseUrl = search.getSearchUrl();
        String platform = search.getPlatform().toLowerCase();

        return switch (platform) {
            case "sutochno" -> buildSutochnoSearchUrl(baseUrl, checkIn, checkOut);
            case "ostrovok" -> buildOstrovokSearchUrl(baseUrl, checkIn, checkOut);
            default -> {
                log.warn("No URL builder for platform '{}', using base URL as-is", platform);
                yield baseUrl;
            }
        };
    }

    private String buildSutochnoSearchUrl(String baseUrl,
                                          LocalDate checkIn, LocalDate checkOut) {
        // https://sutochno.ru/vyborg?checkin=2024-10-15&checkout=2024-10-16
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator
                + "checkin=" + checkIn.format(fmt)
                + "&checkout=" + checkOut.format(fmt);
    }

    private String buildOstrovokSearchUrl(String baseUrl,
                                          LocalDate checkIn, LocalDate checkOut) {
        // https://ostrovok.ru/hotel/russia/vyborg/?dates=15.10.2024-16.10.2024&guests=2
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy");
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator
                + "dates=" + checkIn.format(fmt) + "-" + checkOut.format(fmt)
                + "&guests=2";
    }

    // ==================== HTML fetching ====================

    private String fetchPageHtml(String url) {
        try {
            String ua = USER_AGENTS[ThreadLocalRandom.current().nextInt(USER_AGENTS.length)];
            Document doc = Jsoup.connect(url)
                    .userAgent(ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.3")
                    .header("Accept-Encoding", "gzip, deflate")
                    .header("DNT", "1")
                    .header("Connection", "keep-alive")
                    .timeout(20_000)
                    .followRedirects(true)
                    .get();
            return doc.html();
        } catch (Exception e) {
            log.warn("Failed to fetch {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String truncateHtml(String html, int maxChars) {
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

    // ==================== AI price extraction ====================

    /**
     * Extract multiple competitor prices from a search results page.
     * Works correctly for Sutochno/Ostrovok where search results
     * show real per-date prices (not base prices like Avito).
     */
    private List<ExtractedCompetitorPrice> extractSearchPricesViaAi(
            String apiKey, String html, String platform,
            String city, LocalDate checkIn) {

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", "claude-sonnet-4-6");
            root.put("max_tokens", 2000);
            root.put("system", String.format("""
                Ты извлекаешь цены посуточной аренды квартир из HTML поисковой выдачи площадки %s.
                Город: %s. Дата заезда: %s.
                
                ВАЖНО: на этой площадке цены в выдаче — реальные цены на указанную дату,
                а не базовые/начальные цены. Извлекай их как есть.
                
                Найди ВСЕ объявления на странице и для каждого извлеки:
                - name: краткое название/описание квартиры (адрес, район, комнатность)
                - price: цена за сутки в рублях (число)
                - min_stay: минимальный срок аренды в сутках (если указан, иначе null)
                - url: ссылка на объявление (если есть)
                
                Отвечай СТРОГО JSON (без markdown):
                {"listings": [
                  {"name": "...", "price": 3000, "min_stay": 2, "url": "..."},
                  ...
                ]}
                
                Если цена за месяц — раздели на 30.
                Если цена не определяется — пропусти объявление.
                Извлекай только квартиры посуточно (не длительная аренда).
                Максимум 20 объявлений.
                """, platform, city != null ? city : "неизвестно",
                    checkIn.format(DateTimeFormatter.ISO_LOCAL_DATE)));

            ArrayNode messages = root.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", "Извлеки цены из этой поисковой выдачи:\n\n" + html);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            HttpEntity<String> entity = new HttpEntity<>(
                    objectMapper.writeValueAsString(root), headers);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    API_URL, HttpMethod.POST, entity, JsonNode.class);

            if (response.getBody() == null) return List.of();

            String text = response.getBody().get("content").get(0).path("text").asText();
            return parseSearchPricesResponse(text, checkIn);

        } catch (Exception e) {
            log.warn("AI search price extraction failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ExtractedCompetitorPrice> parseSearchPricesResponse(
            String json, LocalDate checkIn) {
        List<ExtractedCompetitorPrice> result = new ArrayList<>();
        try {
            String cleaned = json.replaceAll("```json|```", "").trim();
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode listings = root.path("listings");
            if (!listings.isArray()) return result;

            for (JsonNode item : listings) {
                String name = item.path("name").asText(null);
                double price = item.path("price").asDouble(0);
                Integer minStay = item.has("min_stay") && !item.get("min_stay").isNull()
                        ? item.get("min_stay").asInt() : null;
                String url = item.path("url").asText(null);

                if (price >= 500 && price <= 50000
                        && name != null && !name.isBlank()) {
                    result.add(new ExtractedCompetitorPrice(
                            name, BigDecimal.valueOf(price), minStay, url, checkIn));
                }
            }

            log.info("AI extracted {} competitor prices from {} search",
                    result.size(), "search");
        } catch (Exception e) {
            log.warn("Cannot parse search prices response: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Legacy: extract single price from a listing page.
     * Works with any platform including Avito — extracts base price.
     */
    private BigDecimal extractPriceViaAi(String apiKey, String html,
                                         String url, String platform) {
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
                    platform, url, html));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", apiKey);
            headers.set("anthropic-version", "2023-06-01");

            HttpEntity<String> entity = new HttpEntity<>(
                    objectMapper.writeValueAsString(root), headers);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    API_URL, HttpMethod.POST, entity, JsonNode.class);

            if (response.getBody() != null && response.getBody().has("content")) {
                String text = response.getBody()
                        .get("content").get(0).path("text").asText().trim();
                String digits = text.replaceAll("[^\\d]", "");
                if (!digits.isEmpty()) {
                    BigDecimal price = new BigDecimal(digits);
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

    // ==================== Competitor analysis for pricing ====================

    /**
     * Get competitor price analysis for a date range.
     * Used by PricingEngine and AiPricingAdvisor.
     */
    public CompetitorAnalysis analyzeCompetitorPrices(
            Long tenantId, LocalDate from, LocalDate to) {

        List<CompetitorDailyPrice> prices =
                dailyPriceRepo.findLatestByTenantAndDateRange(tenantId, from, to);

        if (prices.isEmpty()) {
            return new CompetitorAnalysis(Map.of(), Map.of(), List.of(), 0);
        }

        // Group by date
        Map<LocalDate, List<CompetitorDailyPrice>> byDate = new LinkedHashMap<>();
        for (CompetitorDailyPrice p : prices) {
            byDate.computeIfAbsent(p.getDate(), k -> new ArrayList<>()).add(p);
        }

        Map<LocalDate, BigDecimal> avgByDate = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> minByDate = new LinkedHashMap<>();

        for (var entry : byDate.entrySet()) {
            double avg = entry.getValue().stream()
                    .mapToDouble(p -> p.getPrice().doubleValue())
                    .average().orElse(0);
            double min = entry.getValue().stream()
                    .mapToDouble(p -> p.getPrice().doubleValue())
                    .min().orElse(0);
            avgByDate.put(entry.getKey(), BigDecimal.valueOf(Math.round(avg)));
            minByDate.put(entry.getKey(), BigDecimal.valueOf(Math.round(min)));
        }

        List<String> trends = detectTrends(byDate);

        long competitorCount = prices.stream()
                .map(CompetitorDailyPrice::getCompetitorName)
                .distinct().count();

        return new CompetitorAnalysis(avgByDate, minByDate, trends, (int) competitorCount);
    }

    private List<String> detectTrends(
            Map<LocalDate, List<CompetitorDailyPrice>> byDate) {
        List<String> trends = new ArrayList<>();

        List<LocalDate> dates = new ArrayList<>(byDate.keySet());
        dates.sort(Comparator.naturalOrder());

        if (dates.size() < 2) return trends;

        for (int i = 1; i < dates.size(); i++) {
            LocalDate prev = dates.get(i - 1);
            LocalDate curr = dates.get(i);

            List<CompetitorDailyPrice> prevPrices =
                    byDate.getOrDefault(prev, List.of());
            List<CompetitorDailyPrice> currPrices =
                    byDate.getOrDefault(curr, List.of());

            if (prevPrices.isEmpty() || currPrices.isEmpty()) continue;

            double prevAvg = prevPrices.stream()
                    .mapToDouble(p -> p.getPrice().doubleValue())
                    .average().orElse(0);
            double currAvg = currPrices.stream()
                    .mapToDouble(p -> p.getPrice().doubleValue())
                    .average().orElse(0);

            if (currAvg > prevAvg * 1.10) {
                trends.add(String.format(
                        "%s: конкуренты повышают цены (+%.0f%% vs %s)",
                        curr, (currAvg / prevAvg - 1) * 100, prev));
            } else if (currAvg < prevAvg * 0.90) {
                trends.add(String.format(
                        "%s: конкуренты снижают цены (%.0f%% vs %s)",
                        curr, (1 - currAvg / prevAvg) * 100, prev));
            }
        }

        return trends;
    }

    // ==================== Utility ====================

    private String findApiKey(List<Long> tenantIds) {
        for (Long tid : tenantIds) {
            String key = settings.getValue(tid, "anthropic_api_key");
            if (key != null && !key.isBlank()) return key;
        }
        log.warn("No Anthropic API key configured, skipping competitor scrape");
        return null;
    }

    private String detectPlatform(String url, String platform) {
        if (platform != null && !platform.isBlank()) return platform;
        if (url.contains("sutochno")) return "sutochno";
        if (url.contains("ostrovok")) return "ostrovok";
        if (url.contains("avito")) return "avito";
        if (url.contains("cian")) return "cian";
        if (url.contains("tvil")) return "tvil";
        return "other";
    }

    // ==================== Public query methods ====================

    public List<CompetitorPriceView> getLatestPrices(Long tenantId) {
        return priceRepo.findLatestByTenant(tenantId);
    }

    public BigDecimal getAverageCompetitorPrice(Long tenantId) {
        return priceRepo.avgLatestPrice(tenantId);
    }

    public List<CompetitorDailyPrice> getDailyPrices(
            Long tenantId, LocalDate from, LocalDate to) {
        return dailyPriceRepo.findLatestByTenantAndDateRange(tenantId, from, to);
    }

    // ==================== Records ====================

    public record ScrapeResult(boolean success, BigDecimal price, String error) {}
    public record SearchScrapeResult(boolean success, int pricesExtracted, String error) {}
    public record CompetitorPriceView(String name, String platform,
                                      BigDecimal price, LocalDateTime scrapedAt) {}
    public record ExtractedCompetitorPrice(String name, BigDecimal price,
                                           Integer minStay, String url, LocalDate date) {}
    public record CompetitorAnalysis(
            Map<LocalDate, BigDecimal> avgPriceByDate,
            Map<LocalDate, BigDecimal> minPriceByDate,
            List<String> trends,
            int competitorCount
    ) {}
}
