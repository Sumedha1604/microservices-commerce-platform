# API Gateway

The gateway routes the platform's HTTP APIs and is the authenticated ingress boundary. It validates
Auth Service's HS256 JWTs with the same `AUTH_JWT_SECRET` and `AUTH_JWT_ISSUER` configuration.

- Public: auth register/login/refresh; catalogue, search, and recommendation GETs; health/Prometheus
- Authenticated: user, cart, checkout, order, payment, inventory, and notification APIs
- Admin: `/api/v1/admin/**` and catalogue mutations

Validated subject/role values are forwarded as `X-User-Id` and `X-User-Role`; incoming versions are
always removed first. Explicit user-ID routes are ownership-checked for customers. CORS defaults to
`http://localhost:3000`, does not permit credentials, and is configurable through
`GATEWAY_CORS_ALLOWED_ORIGINS`.

The gateway does not secure services reached directly on their own host ports. See
[`docs/security/security-model.md`](../../docs/security/security-model.md) for the full boundary and
remaining ownership limitations.
