# Project summary

## What this project demonstrates

This repository is a production-inspired commerce backend implemented as a Java 21/Spring Boot
monorepo. Twelve services cover identity, customers, catalogue, inventory, carts, orders, payments,
checkout, notifications, search, recommendations, and edge routing. The focus is the engineering of
distributed boundaries: service-owned data, explicit contracts, reliable events, compensating actions,
security at ingress, observable operations, and repeatable delivery artifacts.

## Strongest engineering decisions

- **Database ownership is explicit.** Ten stateful services use separate PostgreSQL databases,
  credentials, and Flyway histories. Cross-service references are UUIDs rather than shared joins.
- **Consistency choices match the workflow.** Checkout uses synchronous calls where an immediate
  customer answer is required; durable outboxes and Kafka handle payment outcomes, compensation,
  notifications, search, and recommendations.
- **At-least-once behavior is designed, not hidden.** Stable event IDs, transactional consumer claims,
  version guards, bounded retries, DLTs, and documented replay semantics make duplicates safe.
- **Failure recovery is visible.** Checkout performs immediate best-effort rollback, while a
  payment-driven cancellation produces a durable inventory-release compensation event.
- **Read models are independently owned.** Search and recommendation consume the same product stream
  in separate groups and never couple their query paths to Product Service availability.
- **The edge has a clear trust boundary.** The gateway validates Auth Service JWTs, enforces roles,
  removes spoofable identity headers, and applies CORS and HTTP resilience policy.
- **Operational artifacts are first-class.** Metrics, correlated logs, traces, non-root images,
  Kubernetes resources, Kustomize overlays, schema validation, and CI live beside the code.

## Technology and patterns

Java 21, Spring Boot, Spring Cloud Gateway, Spring Security, Spring Data JPA, Flyway, PostgreSQL,
Apache Kafka in KRaft mode, Maven, Testcontainers, Docker/Compose, Kubernetes/Kustomize, Prometheus,
Grafana, Loki, Promtail, Tempo, OpenTelemetry, and GitHub Actions.

Patterns include API Gateway, database per service, transactional outbox, idempotent consumer,
dead-letter topics, CQRS-style read projections, optimistic locking, synchronous orchestration, and
choreographed compensation.

## Scope boundaries and tradeoffs

This is not presented as a finished production platform. Payment and notification delivery are
simulated. Gateway JWT enforcement is not repeated downstream, and opaque-ID ownership enforcement
is incomplete. Kafka and PostgreSQL are single-node locally; outbox/DLT retention, a full projection
backfill mechanism, TLS, managed secrets, autoscaling, alerting, HA/DR, cloud deployment, and automated
releases remain future operational work. There is no frontend or shipping/tax/discount domain.

Those omissions keep the project focused on demonstrable backend and platform fundamentals without
claiming capabilities that are not implemented.

## How to evaluate the repository

1. Read the [system overview](architecture/system-overview.md) for topology and event sequences.
2. Run `mvn test` and inspect service integration tests for failure and duplicate-delivery behavior.
3. Start the base Compose stack and exercise checkout; add Kafka for asynchronous outcomes.
4. Review the event operator guides and architecture decisions for reliability tradeoffs.
5. Render the local Kubernetes overlay and inspect the gateway-only ingress, probes, limits, secrets,
   service discovery, PostgreSQL initialization, Kafka, and Prometheus configuration.

The [documentation index](README.md) links every API, database, event, deployment, and decision guide.
