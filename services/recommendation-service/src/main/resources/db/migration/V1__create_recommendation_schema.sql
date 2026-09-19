-- Recommendation Service read model: a local projection of the product catalogue, maintained
-- asynchronously from product.events.v1, holding only the fields related-product scoring uses.
--
-- No foreign keys: product_id, category_id and brand_id reference product-service data by UUID only.
CREATE TABLE recommendation_product (
    product_id UUID PRIMARY KEY,
    name VARCHAR(200),
    slug VARCHAR(220),
    category_id UUID,
    brand_id UUID,
    price NUMERIC(19, 4),
    currency VARCHAR(3),
    status VARCHAR(20),
    active BOOLEAN,
    -- A deleted product stays as a tombstone (catalogue fields cleared) so an older upsert that is
    -- replayed or redelivered after the delete cannot bring it back.
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    -- product-service's optimistic-lock version of the state held here; strictly increasing per
    -- product. An event whose version is not newer than this is stale and changes nothing.
    source_version BIGINT NOT NULL,
    source_updated_at TIMESTAMPTZ,
    last_event_id UUID NOT NULL,
    indexed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_recommendation_product_live_fields CHECK (
        deleted OR (name IS NOT NULL AND slug IS NOT NULL AND category_id IS NOT NULL AND price IS NOT NULL
                    AND currency IS NOT NULL AND status IS NOT NULL AND active IS NOT NULL)),
    CONSTRAINT ck_recommendation_product_status CHECK (
        status IS NULL OR status IN ('DRAFT', 'ACTIVE', 'INACTIVE', 'DISCONTINUED')),
    CONSTRAINT ck_recommendation_product_version CHECK (source_version >= 0)
);

-- Candidate lookups: only live, active, ACTIVE products are ever recommended.
CREATE INDEX idx_recommendation_candidate_category ON recommendation_product (category_id)
    WHERE NOT deleted AND active AND status = 'ACTIVE';
CREATE INDEX idx_recommendation_candidate_brand ON recommendation_product (brand_id)
    WHERE NOT deleted AND active AND status = 'ACTIVE' AND brand_id IS NOT NULL;
-- Visibility (status/active) over live rows, for operational queries.
CREATE INDEX idx_recommendation_product_visibility ON recommendation_product (status, active) WHERE NOT deleted;

-- Consumer-side deduplication for product.events.v1: claimed with
-- insert ... on conflict (event_id) do nothing in the SAME transaction as the projection change.
CREATE TABLE processed_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    product_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
