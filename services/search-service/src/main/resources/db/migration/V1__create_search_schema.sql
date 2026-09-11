-- Search Service read model: a denormalized, PostgreSQL-native search index of the product
-- catalogue, maintained asynchronously from product.events.v1.
--
-- No foreign keys: product_id, category_id and brand_id are references to product-service data,
-- which this service never reads directly.

-- Trigram matching for case-insensitive partial ("pho" -> "iPhone") search.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE product_search_document (
    product_id UUID PRIMARY KEY,
    sku VARCHAR(100),
    name VARCHAR(200),
    slug VARCHAR(220),
    short_description VARCHAR(500),
    description TEXT,
    category_id UUID,
    brand_id UUID,
    price NUMERIC(19, 4),
    currency VARCHAR(3),
    status VARCHAR(20),
    active BOOLEAN,
    -- A deleted product stays as a tombstone (all catalogue fields cleared) so an older upsert that
    -- is replayed or redelivered after the delete cannot bring it back.
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    -- product-service's optimistic-lock version of the state held here. Strictly increasing per
    -- product; an event whose version is not newer than this is stale and changes nothing.
    source_version BIGINT NOT NULL,
    source_updated_at TIMESTAMPTZ,
    last_event_id UUID NOT NULL,
    indexed_at TIMESTAMPTZ NOT NULL,
    -- Lower-cased haystack for partial matching, trigram-indexed below.
    search_text TEXT GENERATED ALWAYS AS (
        lower(coalesce(name, '') || ' ' || coalesce(sku, '') || ' '
              || coalesce(short_description, '') || ' ' || coalesce(description, ''))
    ) STORED,
    -- Weighted full-text vector: name and SKU outrank short description, which outranks description.
    search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(name, '')), 'A')
        || setweight(to_tsvector('simple', coalesce(sku, '')), 'A')
        || setweight(to_tsvector('english', coalesce(short_description, '')), 'B')
        || setweight(to_tsvector('english', coalesce(description, '')), 'C')
    ) STORED,
    CONSTRAINT ck_product_search_document_live_fields CHECK (
        deleted OR (sku IS NOT NULL AND name IS NOT NULL AND slug IS NOT NULL AND category_id IS NOT NULL
                    AND price IS NOT NULL AND currency IS NOT NULL AND status IS NOT NULL AND active IS NOT NULL)),
    CONSTRAINT ck_product_search_document_status CHECK (
        status IS NULL OR status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'DISCONTINUED')),
    CONSTRAINT ck_product_search_document_version CHECK (source_version >= 0)
);

-- Full-text matching and ranking.
CREATE INDEX idx_product_search_vector ON product_search_document USING GIN (search_vector);
-- Partial / substring matching (LIKE '%term%').
CREATE INDEX idx_product_search_text_trgm ON product_search_document USING GIN (search_text gin_trgm_ops);
-- The default visibility filter (live, active, by status) plus price range and price sorting.
CREATE INDEX idx_product_search_visible ON product_search_document (status, price) WHERE NOT deleted AND active;
CREATE INDEX idx_product_search_category ON product_search_document (category_id) WHERE NOT deleted;
CREATE INDEX idx_product_search_brand ON product_search_document (brand_id) WHERE NOT deleted;

-- Consumer-side deduplication for product.events.v1: claimed with
-- insert ... on conflict (event_id) do nothing in the SAME transaction as the projection change.
CREATE TABLE processed_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    product_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
