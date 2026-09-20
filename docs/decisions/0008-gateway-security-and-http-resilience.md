# ADR 0008: Gateway security and synchronous HTTP resilience

- Status: accepted
- Date: 2026-09-19

## Context

Auth Service already issues short-lived HS256 JWTs with subject and role, but the gateway previously
routed every request without validation. DLT replay and all mutations were externally reachable
through any configured route. Checkout used five unbounded `RestClient` dependencies and mapped most
transport/server failures to generic 500 responses.

## Decision

Use Spring Security resource-server validation at the gateway with the existing shared secret,
issuer, subject, expiry, and role model. Keep catalogue/search/recommendation reads and the required
auth endpoints public; authenticate all other routes; require the existing `ADMIN` role for admin and
catalogue mutation routes. Replace spoofable identity headers with JWT-derived values and enforce the
straightforward user-ID path boundaries.

Use Resilience4j only for Checkout Service's synchronous HTTP calls. Apply finite transport timeouts,
one bounded retry to safe GETs, separate dependency circuit breakers, Micrometer metrics, and
sanitized 502/503/504 errors. Never automatically retry mutations.

## Consequences

The gateway now consistently rejects invalid credentials and prevents unauthenticated DLT replay.
Checkout fails in bounded time and repeated dependency failures fail fast while retaining the current
compensation algorithm and Kafka behavior. Gateway and Auth Service must share secret/issuer
configuration. Host-published downstream ports still bypass gateway enforcement, and ambiguous
mutation timeouts remain until those APIs gain durable idempotency and ownership support.
