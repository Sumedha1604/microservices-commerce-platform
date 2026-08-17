# Product Service API

Base paths: `/api/v1/products`, `/api/v1/categories`, `/api/v1/brands`.

- `POST /products` — 201; `GET /products/{productId}`, `GET /products/slug/{slug}`, `PUT /products/{productId}` — 200; `DELETE /products/{productId}` — 204
- `GET /products` — 200, paginated search with optional `search`, `categoryId`, `brandId`, `status`, `minPrice`, `maxPrice`, `page`, `size`, `sortBy`, `sortDirection`
- `POST /categories` — 201; `GET /categories`, `GET /categories/{categoryId}`, `PUT /categories/{categoryId}` — 200; `DELETE /categories/{categoryId}` — 204
- `POST /brands` — 201; `GET /brands`, `GET /brands/{brandId}`, `PUT /brands/{brandId}` — 200; `DELETE /brands/{brandId}` — 204
- `POST /products/{productId}/images` — 201; `GET /products/{productId}/images` — 200; `PUT /products/{productId}/images/{imageId}` — 200; `DELETE /products/{productId}/images/{imageId}` — 204
- `POST /products/{productId}/attributes` — 201; `GET /products/{productId}/attributes` — 200; `DELETE /products/{productId}/attributes/{attributeId}` — 204

Responses use the shared `ApiResponse`; validation errors use `ErrorResponse`. Health is at `/actuator/health`; development OpenAPI is at `/swagger-ui/index.html`.
