# Inventory Service database

Migration `V1__create_inventory_schema.sql` creates a single `inventory` table:

| Column | Type | Constraints |
|---|---|---|
| `inventory_id` | UUID | primary key |
| `product_id` | UUID | `NOT NULL UNIQUE` |
| `quantity` | INTEGER | `NOT NULL CHECK (quantity >= 0)` |
| `reserved_quantity` | INTEGER | `NOT NULL CHECK (reserved_quantity >= 0)` |
| `created_at` | TIMESTAMPTZ | `NOT NULL` |
| `updated_at` | TIMESTAMPTZ | `NOT NULL` |

A table-level check enforces `reserved_quantity <= quantity`.

`product_id` is a UUID reference only. There is **no foreign key to Product Service** and no cross-service database access — Inventory Service owns its schema independently, verified in `InventoryPostgresIntegrationTest`.
