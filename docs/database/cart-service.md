# Cart Service database

Migration `V1__create_cart_schema.sql` creates two tables.

## `carts`

| Column | Type | Constraints |
|---|---|---|
| `cart_id` | UUID | primary key |
| `user_id` | UUID | `NOT NULL UNIQUE` |
| `created_at` | TIMESTAMPTZ | `NOT NULL` |
| `updated_at` | TIMESTAMPTZ | `NOT NULL` |

`user_id` is a UUID reference only. There is **no foreign key to User Service** and no cross-service database access.

## `cart_items`

| Column | Type | Constraints |
|---|---|---|
| `cart_item_id` | UUID | primary key |
| `cart_id` | UUID | `NOT NULL`, foreign key to `carts.cart_id`, `ON DELETE CASCADE` |
| `product_id` | UUID | `NOT NULL` |
| `quantity` | INTEGER | `NOT NULL CHECK (quantity > 0)` |
| `created_at` | TIMESTAMPTZ | `NOT NULL` |
| `updated_at` | TIMESTAMPTZ | `NOT NULL` |

`UNIQUE(cart_id, product_id)` prevents duplicate line items for the same product in a cart; an index on `cart_id` supports item lookups by cart.

`product_id` is a UUID reference only. There is **no foreign key to Product Service** and no cross-service database access — Cart Service owns its schema independently, verified in `CartPostgresIntegrationTest`.

## Cascade behavior

Deleting a row from `carts` cascades to delete all of its rows in `cart_items` (`ON DELETE CASCADE`), verified in `CartPostgresIntegrationTest`.
