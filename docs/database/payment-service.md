# Payment Service database

Migration `V1__create_payment_schema.sql` creates one table.

## `payments`

| Column | Type | Constraints |
|---|---|---|
| `payment_id` | UUID | primary key |
| `order_id` | UUID | `NOT NULL`, `UNIQUE` |
| `user_id` | UUID | `NOT NULL` |
| `status` | VARCHAR(30) | `NOT NULL` |
| `amount` | NUMERIC(19,2) | `NOT NULL CHECK (amount >= 0)` |
| `currency` | VARCHAR(3) | `NOT NULL` |
| `provider` | VARCHAR(50) | nullable |
| `provider_reference` | VARCHAR(255) | nullable |
| `failure_reason` | VARCHAR(500) | nullable |
| `created_at` | TIMESTAMPTZ | `NOT NULL` |
| `updated_at` | TIMESTAMPTZ | `NOT NULL` |
| `version` | BIGINT | `NOT NULL DEFAULT 0` |

An index on `user_id` supports payment lookups by user. `order_id` carries a `UNIQUE` constraint, enforcing at most one payment per order at the database level. `version` is mapped with JPA `@Version` for optimistic locking (see below); it is never exposed in `PaymentResponse`.

## Cross-service boundary

`order_id` and `user_id` are UUID references only. There is **no foreign key to Order Service or User Service** and no cross-service database access — Payment Service owns its schema independently, verified in `PaymentPostgresIntegrationTest` by asserting zero `FOREIGN KEY` constraints exist on `payments`.

## Money precision

`amount` is `NUMERIC(19,2)`, mapped to Java `BigDecimal` — no `float`/`double` is used anywhere in the money path. Values are persisted and read back with scale 2; an amount supplied with more than two decimal places (e.g. `10.005`) is rounded half-up to `10.01` before being persisted, and the value returned by the API always equals the persisted value. Verified in `PaymentPostgresIntegrationTest`.

## Optimistic locking

`payments.version` backs JPA `@Version` on the `Payment` entity. Concurrent transitions on the same payment (e.g. one request authorizing while another cancels) are detected instead of silently overwriting each other: only one commits, and the other fails with an optimistic locking exception, mapped to `409 CONFLICT` at the API layer. Verified with a real concurrent-transaction test in `PaymentPostgresIntegrationTest`.
