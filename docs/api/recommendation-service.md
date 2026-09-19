# Recommendation Service API

Base path: `/api/v1/recommendations`. Read-only. Routed through the API gateway
(`/api/v1/recommendations/**` → recommendation-service, port 8091).

- `GET /api/v1/recommendations/products/{productId}`: 200, related products for one product

There is **no** popular/trending endpoint: the platform has no trustworthy purchase signal yet (see
[../decisions/0007-product-recommendations.md](../decisions/0007-product-recommendations.md)).

Responses use the shared `ApiResponse`; errors use `ErrorResponse`. Health is at `/actuator/health`,
metrics at `/actuator/prometheus`, development OpenAPI at `/swagger-ui/index.html`.

## Parameters

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `productId` (path) | UUID | required | The source product |
| `limit` | int | `10` | `1..50` |

## Response: `RelatedProductsResponse`

| Field | Notes |
|---|---|
| `sourceProductId` | |
| `strategy` | `CONTENT_BASED_V1` |
| `limit` | the applied limit |
| `items` | best first; may be empty |

Each item (`ProductRecommendation`):

| Field | Notes |
|---|---|
| `productId`, `name`, `slug` | |
| `categoryId`, `brandId` | ids only (`brandId` may be null) |
| `price`, `currency` | |
| `score` | sum of the matched rules' points |
| `reasons` | subset of `SAME_CATEGORY`, `SAME_BRAND`, `SIMILAR_PRICE`, in that order |

```json
{
  "success": true,
  "data": {
    "sourceProductId": "240e…",
    "strategy": "CONTENT_BASED_V1",
    "limit": 10,
    "items": [
      { "productId": "8a1c…", "name": "Acme Phone Mini", "slug": "acme-phone-mini",
        "categoryId": "a1b2…", "brandId": "c3d4…", "price": 110.00, "currency": "USD",
        "score": 8, "reasons": ["SAME_CATEGORY", "SAME_BRAND", "SIMILAR_PRICE"] }
    ]
  }
}
```

## How results are chosen

| Rule | Points |
|---|---|
| same category | 5 |
| same brand (only if the source has a brand) | 2 |
| same currency and price within ±20% of the source's price (inclusive) | 1 |

- Only products sharing the source's **category or brand** are returned. Price alone never qualifies
  a product.
- Only live, `active`, `ACTIVE` products are recommended. The source itself never is.
- Order: `score` desc, then `name` asc (binary/byte order: uppercase before lowercase), then
  `productId` asc. The same catalogue always yields the same list.
- Prices in different currencies are never compared (no FX conversion).

## Source product

| Source state in the local catalogue | Result |
|---|---|
| unknown (never seen, or its events are no longer retained) | `404 RESOURCE_NOT_FOUND` |
| deleted | `404 RESOURCE_NOT_FOUND` |
| live but `DRAFT`/`INACTIVE`/`DISCONTINUED` or `active=false` | `200`, recommendations computed normally |
| live with nothing related | `200` with empty `items` |

## Errors

- `400 BAD_REQUEST`: malformed `productId`, non-integer `limit`, `limit` outside `1..50`.
- `404 RESOURCE_NOT_FOUND`: see above.
- `405 METHOD_NOT_ALLOWED`: any non-GET method.
- `500 INTERNAL_SERVER_ERROR`: sanitized message.

## Consistency

Results reflect product events applied so far: **eventually consistent** with product-service, and
never computed by calling it. Only products whose events are retained on `product.events.v1` are known.

## Security

Public and unauthenticated, like product-service's catalogue reads and search-service. It exposes
only catalogue fields those APIs already expose. No platform service validates tokens yet.
