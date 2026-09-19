# Recommendation Service database

Own PostgreSQL database (`recommendation_db`), migrated by Flyway
(`V1__create_recommendation_schema.sql`), accessed with plain JDBC. No foreign keys: `product_id`,
`category_id` and `brand_id` reference product-service data by UUID only. There is no access to any
other service's database, including search-service's similar projection.

## `recommendation_product`

One row per product ever seen, including deleted products as tombstones. It holds only what
related-product scoring and visibility need.

| Column | Type | Notes |
|---|---|---|
| `product_id` | UUID | primary key |
| `name`, `slug` | VARCHAR | null only on tombstones |
| `category_id` | UUID | null only on tombstones |
| `brand_id` | UUID | nullable |
| `price` | NUMERIC(19,4) | |
| `currency` | VARCHAR(3) | stored upper-case |
| `status` | VARCHAR(20) | `CHECK` against product statuses |
| `active` | BOOLEAN | |
| `deleted` | BOOLEAN | tombstone flag |
| `source_version` | BIGINT | product-service version of the held state; `>= 0` |
| `source_updated_at` | TIMESTAMPTZ | |
| `last_event_id` | UUID | event that produced this state |
| `indexed_at` | TIMESTAMPTZ | when it was applied here |

`ck_recommendation_product_live_fields`: a non-deleted row must have every required field.

### Indexes

| Index | Definition | Serves |
|---|---|---|
| `recommendation_product_pkey` | `product_id` | source lookup, upsert conflict target |
| `idx_recommendation_candidate_category` | `category_id WHERE NOT deleted AND active AND status = 'ACTIVE'` | same-category candidates |
| `idx_recommendation_candidate_brand` | `brand_id WHERE NOT deleted AND active AND status = 'ACTIVE' AND brand_id IS NOT NULL` | same-brand candidates |
| `idx_recommendation_product_visibility` | `(status, active) WHERE NOT deleted` | visibility queries |

### Writes

Both are single statements with `ON CONFLICT … DO UPDATE … WHERE stored.source_version < incoming`:

- **Upsert:** replaces the row only for a strictly newer version, and never on a tombstone.
- **Tombstone:** sets `deleted = true` and clears catalogue fields, only for a strictly newer version.

A write that updates 0 rows is a stale event: recorded as processed, and it changes nothing.

### Ranking query

One self-join (`source s` × `candidate c`) filtered to live, active, `ACTIVE` candidates sharing the
source's category or brand. It orders by the bound weights (5/2/1), then `c.name COLLATE "C"`, then
`c.product_id`, with `LIMIT`. See [../api/recommendation-service.md](../api/recommendation-service.md).

## `processed_event`

| Column | Type | Notes |
|---|---|---|
| `event_id` | UUID | primary key; claimed with `on conflict do nothing` |
| `event_type` | VARCHAR(100) | |
| `product_id` | UUID | |
| `processed_at` | TIMESTAMPTZ | |

The claim and the projection write share one transaction.

## Not present

There is no popularity or purchase aggregate table: no trustworthy purchase signal exists yet.

## Retention

Neither table is pruned. Tombstones must remain as long as older events for the product could be replayed.
