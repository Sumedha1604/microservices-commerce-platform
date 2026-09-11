# Product Service database

Migration `V1__create_product_schema.sql` creates `categories`, `brands`, `products`, `product_images`, and `product_attributes`. `products.category_id` and `products.brand_id` reference `categories`/`brands`; `product_images` and `product_attributes` reference `products`. `categories.parent_category_id` is self-referential for category hierarchy. Foreign keys are internal to this service only.

## V2: optimistic locking

`V2__add_product_version.sql` adds `products.version BIGINT NOT NULL DEFAULT 0`, mapped with JPA `@Version`. Concurrent stale writes fail instead of overwriting each other, and every committed change gets a strictly increasing version that product events carry.

## V3: product outbox

`V3__create_product_outbox_event.sql` creates `product_outbox_event` (same shape as `payment_outbox_event`): `id`, `aggregate_type` (`Product`), `aggregate_id` (productId), `event_id` (unique), `event_type`, `schema_version`, `topic`, `event_key`, `payload` (serialized envelope), `status` (`PENDING`/`PUBLISHED`), `created_at`, `published_at`, `attempt_count`, `last_error`, `next_attempt_at`. Indexes: `(status, next_attempt_at, created_at)` for polling, `aggregate_id`, and a partial `(aggregate_id, created_at, id) WHERE status = 'PENDING'` for the per-product ordering guard. Rows are never pruned.
