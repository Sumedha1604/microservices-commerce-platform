# Order Service database

Migration `V1__create_order_schema.sql` creates two tables.

## `orders`

| Column | Type | Constraints |
|---|---|---|
| `order_id` | UUID | primary key |
| `user_id` | UUID | `NOT NULL` |
| `status` | VARCHAR(30) | `NOT NULL` |
| `subtotal` | NUMERIC(19,2) | `NOT NULL CHECK (subtotal >= 0)` |
| `total` | NUMERIC(19,2) | `NOT NULL CHECK (total >= 0)` |
| `currency` | VARCHAR(3) | `NOT NULL` |
| `created_at` | TIMESTAMPTZ | `NOT NULL` |
| `updated_at` | TIMESTAMPTZ | `NOT NULL` |
| `version` | BIGINT | `NOT NULL DEFAULT 0` |

An index on `user_id` supports order lookups by user. `version` is mapped with JPA `@Version` for optimistic locking (see below); it is never exposed in `OrderResponse`.

`user_id` is a UUID reference only. There is **no foreign key to User Service** and no cross-service database access.

## `order_items`

| Column | Type | Constraints |
|---|---|---|
| `order_item_id` | UUID | primary key |
| `order_id` | UUID | `NOT NULL`, foreign key to `orders.order_id`, `ON DELETE CASCADE` |
| `product_id` | UUID | `NOT NULL` |
| `product_name` | VARCHAR(255) | `NOT NULL` |
| `sku` | VARCHAR(100) | nullable |
| `unit_price` | NUMERIC(19,2) | `NOT NULL CHECK (unit_price >= 0)` |
| `quantity` | INTEGER | `NOT NULL CHECK (quantity > 0)` |
| `line_total` | NUMERIC(19,2) | `NOT NULL CHECK (line_total >= 0)` |
| `created_at` | TIMESTAMPTZ | `NOT NULL` |

An index on `order_id` supports item lookups by order.

`product_id` is a UUID reference only. There is **no foreign key to Product Service** and no cross-service database access — Order Service owns its schema independently, verified in `OrderPostgresIntegrationTest`. `product_name`, `sku`, and `unit_price` are a point-in-time snapshot captured at order creation, not a live reference to Product Service data.

## Money precision

`subtotal`, `total`, `unit_price`, and `line_total` are all `NUMERIC(19,2)`, mapped to Java `BigDecimal` — no `float`/`double` is used anywhere in the money path. Values are persisted and read back with scale 2, verified in `OrderPostgresIntegrationTest`.

## Cascade behavior

Deleting a row from `orders` cascades to delete all of its rows in `order_items` (`ON DELETE CASCADE`), verified in `OrderPostgresIntegrationTest`.

## Optimistic locking

`orders.version` backs JPA `@Version` on the `Order` entity. Concurrent transitions on the same order (e.g. one request confirming while another cancels) are detected instead of silently overwriting each other: only one commits, and the other fails with an optimistic locking exception, mapped to `409 CONFLICT` at the API layer. Verified with a real concurrent-transaction test in `OrderPostgresIntegrationTest`.
