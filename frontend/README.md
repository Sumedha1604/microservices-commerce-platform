# Northstar frontend

React 19, TypeScript, Vite, React Router, Lucide icons, and plain responsive CSS provide the browser
experience for the Microservices Commerce Platform. All API traffic uses the single
`VITE_API_BASE_URL`; its default is `http://localhost:8080`, the API Gateway. Browser code never
targets ports 8081–8091.

## Run locally

```bash
cd frontend
cp .env.example .env
npm ci
npm run dev
```

Vite listens on `http://localhost:3000`, matching the gateway's default CORS origin. Override
`VITE_API_BASE_URL` at build/start time when the gateway is elsewhere.

## Routes

Public routes are `/`, `/search`, `/products/:id`, `/login`, and `/register`. Authenticated routes
are `/cart`, `/checkout`, `/orders`, `/orders/:id`, `/notifications`, and `/account`. ADMIN tokens
add `/admin/products`, `/admin/dlt`, and `/admin/dlt/:id`; route guards enforce the role in addition
to the gateway's authoritative policy.

## Authentication

Login and registration return access and refresh tokens. The application keeps that response in
`sessionStorage`, injects the access token centrally, performs one refresh-token rotation after an
authenticated 401, and clears the session when refresh fails. Logout clears the browser session
even if the revocation call is unreachable. `sessionStorage` limits persistence to the tab but is
still readable by JavaScript, so strong CSP, dependency review, and XSS prevention remain essential.
No signing secret is present in the frontend.

## Verified API contract map

All paths below are gateway-relative and successful bodies use `ApiResponse<T>` except 204 replies.
Errors use `errorCode`, `message`, `statusCode`, and `timestamp`; pages use `items`, zero-based
`page`, `size`, `totalElements`, `totalPages`, `hasNext`, and `hasPrevious`.

| Area | Methods and paths | Access / notable contract |
|---|---|---|
| Auth | `POST /api/v1/auth/register`, `/login`, `/refresh`, `/logout` | first three public; logout authenticated; email/password, rotated refresh tokens; roles `CUSTOMER`, `ADMIN`, `SUPPORT` |
| Products | `GET /api/v1/products`, `GET /products/{id}`; `POST`, `PUT /{id}`, `DELETE /{id}` | reads public; mutations ADMIN; statuses `DRAFT`, `ACTIVE`, `INACTIVE`, `DISCONTINUED` |
| Search | `GET /api/v1/search/products` | public; `q`, category/brand, price, currency, status, five documented sorts, page/size |
| Recommendations | `GET /api/v1/recommendations/products/{id}?limit=` | public; backend strategy `CONTENT_BASED_V1` |
| Cart | create/get-by-user; add, patch, remove and clear items under `/api/v1/carts` | authenticated; backend stores product IDs and quantities only |
| Checkout | `POST /api/v1/checkouts` with `cartId` | authenticated; returns real cart/order/payment IDs, statuses, total and currency |
| Orders | `GET /api/v1/orders/user/{userId}`, `GET /orders/{id}` | authenticated; `PENDING`, `CONFIRMED`, `CANCELLED` |
| Notifications | `GET /api/v1/notifications?userId=&page=&size=` | authenticated, read-only; `CREATED` internal records; no read/unread API |
| Profile | profile create/get/update, address reads, preference get/update under `/api/v1/users/{userId}` | authenticated and gateway ownership-filtered |
| DLT | list/detail/replay under `/api/v1/admin/dlt/payment-events` | ADMIN; `NEW`, `REPLAYED`, `REPLAY_FAILED`; replay identifies a stored record only |

## Validation

```bash
npm run lint
npm test
npm run build
docker build -f frontend/Dockerfile -t commerce/frontend:local . # repository root
```

Tests cover auth state and route guards, API envelope/error handling, product rendering, cart
quantity behavior, and checkout results.

## Current UI boundaries

- Product image placeholders are explicit when the Product API has no image; no fake media is used.
- Cart display totals use current Product API prices; Checkout remains authoritative.
- Notifications have no read/unread state because the API is read-only.
- Saved addresses are displayed; address create/edit/delete UI is not included yet.
- Product admin covers list/create/edit/delete. Category, brand, image, and attribute administration
  remains API-only.
- The backend has no real payment provider, shipping, tax, discount, review, or tracking contract.
