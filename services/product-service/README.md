# Product Service

Implements the product catalogue: products, categories, brands, product images, and product attributes, backed by PostgreSQL and Flyway. Entities are related within this service's own database only.

## Domain entities

- **Product** — SKU, name, slug, description, category, brand, price/currency, status (`DRAFT`, `ACTIVE`, `INACTIVE`, `DISCONTINUED`), active flag
- **Category** — name, slug, description, optional parent category (self-referential), active flag
- **Brand** — name, slug, description, active flag
- **ProductImage** — URL, alt text, sort order, primary-image flag, owned by a product
- **ProductAttribute** — free-form name/value pair, owned by a product

## API

All responses use the shared `ApiResponse` envelope; errors use `ErrorResponse`.

- `/api/v1/products` — create (`201`), get by id, get by slug, update, delete (`204`), and search (paginated)
- `/api/v1/categories` — create (`201`), list, get, update, delete (`204`)
- `/api/v1/brands` — create (`201`), list, get, update, delete (`204`)
- `/api/v1/products/{productId}/images` — create (`201`), list, update, delete (`204`)
- `/api/v1/products/{productId}/attributes` — create (`201`), list, delete (`204`)

### Catalogue search

`GET /api/v1/products` filters on `search` (case-insensitive match on product name), `categoryId`, `brandId`, `status`, `minPrice`, and `maxPrice` — all optional and combinable.

### Pagination and sorting

`page` (default `0`), `size` (default `20`, max `100`), `sortBy` (default `createdAt`), and `sortDirection` (`ASC`/`DESC`, default `ASC`) control the result page. Responses report `items`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`, and `hasPrevious`.

### Validation and error handling

Request DTOs validate required fields, string length, decimal minimums, and slug/currency format via Bean Validation. Validation failures and malformed JSON return `400` with `ErrorResponse`; domain errors (e.g. not found) return the status carried by `CommerceException`; unhandled exceptions return `500`.

## Running locally

PostgreSQL must be reachable at the configured `PRODUCT_DB_URL`. Copy `.env.example`, adjust as needed (do not create or commit a real `.env`), export the variables, and run:

```
mvn -pl services/product-service -am spring-boot:run
```

Flyway runs `V1__create_product_schema.sql` automatically on startup, creating `categories`, `brands`, `products`, `product_images`, and `product_attributes`.

## Tests

```
mvn -pl services/product-service -am clean test
mvn -pl services/product-service -am clean verify
```

Integration coverage (`ProductPostgresIntegrationTest`) uses Testcontainers to run migrations and repository/service behavior against a real PostgreSQL 16 instance; Docker must be running for that test to execute.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `PRODUCT_DB_URL` | `jdbc:postgresql://localhost:5432/product_db` | Datasource URL |
| `PRODUCT_DB_USERNAME` | `product_user` | Datasource username |
| `PRODUCT_DB_PASSWORD` | `change-me` | Datasource password |
| `PRODUCT_SERVER_PORT` | `8083` | HTTP port |

## Docker

`docker build -f services/product-service/Dockerfile .` from the repository root builds a runnable image (multi-stage, matching the other services). The image has no Docker `HEALTHCHECK`, consistent with the rest of the platform: the minimal Java runtime has no HTTP client, so orchestration should probe `/actuator/health` instead.

## Implementation notes

- Service port: `8083`.
- `/actuator/health` is exposed for orchestration; other actuator endpoints are not.
- Planned but not implemented: Kafka events, inventory, and order integration.
