# Search Service API

Base path: `/api/v1/search`. Read-only. Routed through the API gateway (`/api/v1/search/**` →
search-service, port 8090).

- `GET /search/products`: 200, paginated product search

No write endpoints. The index changes only through `product.events.v1` (see
[../events/product-events.md](../events/product-events.md)). Other methods return `405`.

Responses use the shared `ApiResponse` / `PageResponse`; errors use `ErrorResponse`. Health is at
`/actuator/health`, metrics at `/actuator/prometheus`, development OpenAPI at `/swagger-ui/index.html`.

## Parameters

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `q` | string | none | Up to 200 characters. Blank means browse (no text match). |
| `categoryId` | UUID | none | |
| `brandId` | UUID | none | |
| `minPrice` | decimal | none | inclusive, `>= 0` |
| `maxPrice` | decimal | none | inclusive, `>= 0`, `>= minPrice` |
| `currency` | string | none | 3 letters, case-insensitive |
| `status` | string | `ACTIVE` | `DRAFT`, `ACTIVE`, `INACTIVE`, `DISCONTINUED` |
| `sort` | string | `relevance` | `relevance`, `priceAsc`, `priceDesc`, `nameAsc`, `nameDesc` (case-insensitive) |
| `page` | int | `0` | `>= 0` |
| `size` | int | `20` | `1..100` |

Examples:

```
GET /api/v1/search/products?q=iphone
GET /api/v1/search/products?q=phone&minPrice=100&maxPrice=1000
GET /api/v1/search/products?q=laptop&page=0&size=20
GET /api/v1/search/products?categoryId=…&sort=priceAsc
```

## Matching and ranking

- Case-insensitive. Matches whole words, with English stemming (`phones` finds *phone*), **or**
  substrings (`lapt` finds *Laptop*, `PH-10` finds SKU `PH-100`) across name, SKU, short description
  and description.
- `relevance` ranks an exact name or SKU match first, then name prefix, then name substring. Full-text
  rank (name/SKU weighted above descriptions) and name similarity break the rest, so a product named
  *Phone Case* outranks a laptop whose description mentions a phone. Without `q`, `relevance` means
  name order.
- Partial matching is substring-based, so `phone` also matches *Wireless Head**phone**s*. Whole-word
  name matches always rank above such substring-only matches.
- Every sort ends with a unique tie-breaker, so pages never overlap or skip.
- Special characters are treated literally: `%` and `_` are not wildcards.

## Visibility

Only products that are **not deleted** and **`active = true`** are returned, with status `ACTIVE`
unless `status` is given. Deleted products are never returned. A new product is `DRAFT` and appears
once it is activated.

## Response item: `ProductSearchResult`

| Field | Notes |
|---|---|
| `productId`, `sku`, `name`, `slug`, `shortDescription`, `description` | |
| `categoryId`, `brandId` | ids only |
| `price`, `currency`, `status` | |
| `version` | product-service version this result reflects |
| `updatedAt` | product-service's last change |
| `indexedAt` | when search-service applied it (eventual-consistency lag) |

## Errors

- `400 BAD_REQUEST`: malformed UUID/decimal/int parameters, `size` outside `1..100`, `page < 0`,
  `q` longer than 200, negative prices, `minPrice > maxPrice`, invalid `currency`, `status` or `sort`.
- `405 METHOD_NOT_ALLOWED`: any non-GET method.
- `500 INTERNAL_SERVER_ERROR`: sanitized message.

## Consistency

Results are **eventually consistent** with product-service: a change is searchable after the outbox
publishes it (1 s poll by default) and the consumer applies it. search-service never calls
product-service to answer a query.

## Security

Public and unauthenticated, like product-service's catalogue reads. It exposes nothing the
catalogue API does not already expose. No platform service validates tokens yet.
