# Search Service database

Own PostgreSQL database (`search_db`), migrated by Flyway (`V1__create_search_schema.sql`). Accessed
with plain JDBC: the read model is native search SQL. No foreign keys: `product_id`, `category_id` and
`brand_id` reference product-service data by UUID only.

## Extension

`CREATE EXTENSION IF NOT EXISTS pg_trgm`: trigram operators and GIN operator class for partial matching.

## `product_search_document`

One row per product ever seen, including deleted ones (as tombstones).

| Column | Type | Notes |
|---|---|---|
| `product_id` | UUID | primary key |
| `sku`, `name`, `slug` | VARCHAR | null only on tombstones |
| `short_description`, `description` | VARCHAR(500), TEXT | nullable |
| `category_id`, `brand_id` | UUID | brand nullable |
| `price` | NUMERIC(19,4) | |
| `currency` | VARCHAR(3) | |
| `status` | VARCHAR(20) | `CHECK` against product statuses |
| `active` | BOOLEAN | |
| `deleted` | BOOLEAN | tombstone flag |
| `source_version` | BIGINT | product-service version of the held state; `>= 0` |
| `source_updated_at` | TIMESTAMPTZ | product-service `updated_at` |
| `last_event_id` | UUID | event that produced this state |
| `indexed_at` | TIMESTAMPTZ | when it was applied here |
| `search_text` | TEXT, **generated** | `lower(name ‖ sku ‖ short_description ‖ description)` |
| `search_vector` | TSVECTOR, **generated** | weighted: name/sku A, short description B, description C |

`ck_product_search_document_live_fields`: a non-deleted row must have every required catalogue field.

### Indexes

| Index | Definition | Serves |
|---|---|---|
| `product_search_document_pkey` | `product_id` | upsert conflict target |
| `idx_product_search_vector` | GIN `search_vector` | full-text match and rank |
| `idx_product_search_text_trgm` | GIN `search_text gin_trgm_ops` | `LIKE '%term%'` partial match |
| `idx_product_search_visible` | `(status, price) WHERE NOT deleted AND active` | default visibility, price range/sort |
| `idx_product_search_category` | `category_id WHERE NOT deleted` | category filter |
| `idx_product_search_brand` | `brand_id WHERE NOT deleted` | brand filter |

### Writes

Both writes are single statements whose `ON CONFLICT … DO UPDATE … WHERE` clause compares
`source_version`. An event not newer than the stored state updates 0 rows, which makes it stale.
Upserts never overwrite a tombstone. See [../events/product-events.md](../events/product-events.md).

## `processed_event`

| Column | Type | Notes |
|---|---|---|
| `event_id` | UUID | primary key; claimed with `on conflict do nothing` |
| `event_type` | VARCHAR(100) | |
| `product_id` | UUID | |
| `processed_at` | TIMESTAMPTZ | |

The claim runs in the same transaction as the projection write, so both commit or neither does.

## Retention

Neither table is pruned. Tombstones must be kept for as long as older events for that product could
still be replayed.
