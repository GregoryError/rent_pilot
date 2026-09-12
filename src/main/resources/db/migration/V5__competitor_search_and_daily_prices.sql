-- ==========================================================
-- V5: Enhanced competitor analysis with per-date prices
--     and search-based scraping
-- ==========================================================

-- New table: competitor search queries (search results, not individual pages)
-- Each search covers a city/area and returns multiple competitors with prices
CREATE TABLE competitor_searches (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES tenants(id),
    property_id         BIGINT REFERENCES properties(id),
    platform            VARCHAR(100) NOT NULL,
    search_url          VARCHAR(2000) NOT NULL,
    search_name         VARCHAR(255),
    city                VARCHAR(255),
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    last_scraped_at     TIMESTAMP,
    scrape_interval_hours INTEGER NOT NULL DEFAULT 12,
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);

-- Per-date competitor prices extracted from search results
CREATE TABLE competitor_daily_prices (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT NOT NULL REFERENCES tenants(id),
    search_id           BIGINT REFERENCES competitor_searches(id),
    listing_id          BIGINT REFERENCES competitor_listings(id),
    platform            VARCHAR(100) NOT NULL,
    competitor_name     VARCHAR(500),
    competitor_url      VARCHAR(2000),
    date                DATE NOT NULL,
    price               NUMERIC(10,2) NOT NULL,
    min_stay            INTEGER,
    scraped_at          TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_comp_daily_tenant_date
    ON competitor_daily_prices(tenant_id, date);

CREATE INDEX idx_comp_daily_search_date
    ON competitor_daily_prices(search_id, date);

CREATE INDEX idx_comp_daily_scraped
    ON competitor_daily_prices(scraped_at);

-- Add date index on existing competitor_prices
CREATE INDEX IF NOT EXISTS idx_comp_prices_date
    ON competitor_prices(listing_id, date);

-- Settings for competitor scraping
INSERT INTO settings (tenant_id, key, value, description)
SELECT 1, 'competitor_search_enabled', 'true', 'Включить поиск конкурентов через поисковую выдачу'
WHERE NOT EXISTS (SELECT 1 FROM settings WHERE key = 'competitor_search_enabled');

INSERT INTO settings (tenant_id, key, value, description)
SELECT 1, 'competitor_scrape_delay_ms', '5000', 'Задержка между запросами при парсинге (мс)'
WHERE NOT EXISTS (SELECT 1 FROM settings WHERE key = 'competitor_scrape_delay_ms');
