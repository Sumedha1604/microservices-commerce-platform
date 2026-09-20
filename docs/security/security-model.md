# Security model

## Ingress and authentication

The API Gateway is the authenticated ingress boundary. It validates the access-token format already
issued by Auth Service: an HS256 JWT signed with `AUTH_JWT_SECRET`, issuer
`AUTH_JWT_ISSUER`, subject equal to the auth user UUID, a single `role` claim, `iat`, and `exp`.
The secret must be at least 32 bytes and must be identical in Auth Service and the gateway. Access
tokens default to 15 minutes. Refresh tokens are opaque 48-byte random values, stored only as SHA-256
digests, valid for 30 days by default, and rotated (the old record is revoked) on refresh.

Public gateway routes are:

- `POST /api/v1/auth/register`, `/login`, and `/refresh`
- `GET /api/v1/products/**`, `/categories/**`, and `/brands/**`
- `GET /api/v1/search/**` and `/recommendations/**`
- `/actuator/health` and `/actuator/prometheus`
- CORS preflight requests

All other routed APIs require a valid JWT. Catalogue mutations and `/api/v1/admin/**`, including DLT
inspection and replay, require `role=ADMIN`. Auth Service has an existing persisted
`CUSTOMER`/`ADMIN`/`SUPPORT` role and only signed tokens can assert it; public registration always
creates `CUSTOMER`. Admin provisioning remains an operational database/bootstrap responsibility.

Missing, malformed, incorrectly signed, expired, or wrong-issuer tokens receive a sanitized `401`.
Authenticated callers without the required role receive a sanitized `403`.

## Trusted identity and ownership

The gateway removes client-supplied `X-User-Id` and `X-User-Role`, then derives those two headers from
the validated JWT. No other claims or the raw token are copied. Customer access to
`/api/v1/users/{userId}/**` and the existing `/carts|orders|payments/user/{userId}` queries is checked
against the JWT subject; `ADMIN` and `SUPPORT` may cross that boundary.

APIs addressed only by opaque cart, order, payment, notification, or address IDs cannot be fully
ownership-checked at the gateway without a downstream ownership lookup. Those contracts remain
authenticated but require service-level ownership enforcement in a future milestone. Checkout still
accepts a `cartId`; it does not yet prove that the authenticated subject owns that cart.

## Direct service access

Gateway enforcement is not downstream resource-server enforcement. The current E2E Compose file
publishes every service port to the host for testing, so a caller with host access can bypass the
gateway. Do not describe those host-published environments as externally secured. A deployment must
publish only port 8080 and keep service ports on a private network, or add JWT validation to each
downstream service. This milestone intentionally retains the host mappings so existing direct-service
E2E tests continue to work.

## Browser and operational surface

CORS uses an explicit `GATEWAY_CORS_ALLOWED_ORIGINS` list (local default
`http://localhost:3000`), a bounded method/header allow-list, and no credentials. Responses include
`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, and `Referrer-Policy: no-referrer`.

Every service exposes only Actuator health and Prometheus. Health details are hidden. Metrics remain
public at the gateway and host-published service ports for the current local observability stack;
production deployments should put Prometheus and health probes on the private network. Sensitive
Actuator endpoints such as env, configprops, beans, and heapdump are not exposed.

## Configuration and secrets

No real credential is tracked. `.env.example` files contain placeholders. Database `change-me`
values and the shared JWT value in `application.yml` are local-development defaults only. Any shared
or production environment must set database password variables and a randomly generated
`AUTH_JWT_SECRET` of at least 32 bytes. `AUTH_JWT_ISSUER` must also agree across issuer and gateway.
Tokens, passwords, authorization headers, and secrets are not logged.

Remaining debt: private service networking or downstream JWT validation; service-level ownership for
opaque resource IDs; managed secret storage/rotation; and an explicit admin provisioning workflow.
