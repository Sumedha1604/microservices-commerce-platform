# Test strategy

## Goals

The suite protects domain rules, API contracts, persistence behavior, messaging reliability,
cross-service workflows, and deployability. Tests are layered so the default Maven reactor remains
repeatable while infrastructure-heavy scenarios are explicit.

## Test layers

| Layer | Location | What it proves | Infrastructure |
|---|---|---|---|
| Unit/component | each service `src/test` | domain transitions, validation, mapping, gateway policy, retry/compensation logic | mocks or in-process context |
| Frontend unit/component | `frontend/src/**/*.test.*` | auth state, route guards, API errors, product, cart, and checkout UI behavior | Vitest and jsdom |
| Persistence integration | service integration tests | Flyway schemas, PostgreSQL constraints, repositories, outbox and deduplication transactions | Testcontainers PostgreSQL where required |
| Messaging integration | producer/consumer service tests | serialization, retry/DLT behavior, idempotency, ordering/version guards | embedded/mocked broker components plus PostgreSQL as defined by each module |
| End-to-end base | `tests/end-to-end` | health and synchronous checkout success/failure compensation across real services | Docker Compose; checkout scenarios require `-De2e.base=true` |
| End-to-end Kafka | `tests/end-to-end` opt-in tests | payment/order/notification flow, compensation, search, and recommendations | Compose Kafka overlay and explicit system properties |
| Deployment validation | CI and local commands | image buildability, Kustomize rendering, Kubernetes schema validity | Docker, kubectl/Kustomize, kubeconform |

## Standard validation

From the repository root:

```bash
mvn test
cd frontend && npm ci && npm run lint && npm test && npm run build
mvn package -DskipTests
git diff --check
kubectl kustomize infrastructure/kubernetes/overlays/local >/tmp/commerce.yaml
```

`mvn test` is the CI test entry point. The end-to-end module is part of the reactor, but checkout
scenarios that require running services are explicitly opt-in. Health probes use assumptions and
report individual unavailable services as skipped. This keeps a clean checkout deterministic while
allowing the same module to exercise a live Compose stack.

## Running the base end-to-end suite

```bash
docker compose -f tests/end-to-end/compose.yml up -d
mvn -pl tests/end-to-end test -De2e.base=true
docker compose -f tests/end-to-end/compose.yml down
```

The base suite covers service health plus checkout success, inventory failure, and order failure.
It uses unique test data and bounded HTTP timeouts. See the [E2E guide](../../tests/end-to-end/README.md)
for environment overrides and exact assertions.

## Kafka-backed end-to-end suites

Start the required services with `infrastructure/kafka/compose.kafka.yml`, then enable only the
scenario being exercised:

```bash
mvn -pl tests/end-to-end test -De2e.kafka=true
mvn -pl tests/end-to-end test -De2e.kafka=true -De2e.notification=true
mvn -pl tests/end-to-end test -De2e.kafka=true -De2e.search=true
mvn -pl tests/end-to-end test -De2e.kafka=true -De2e.recommendation=true
```

These tests poll eventually consistent outcomes with bounded deadlines. Lower-level duplicate,
retry, DLT, and stale-version behavior remains in the owning service's integration suite rather than
being redundantly asserted end to end.

## CI coverage

The required `CI` workflow validates the frontend with Node 20 (`npm ci`, lint, tests, build), runs
the complete Maven reactor, packages all modules, renders the local
Kustomize overlay, and validates the non-secret base against Kubernetes schemas. The manually
dispatched Docker workflow builds every service image without publishing it. CI deliberately does
not deploy a cluster, publish images, or run the resource-heavy Kafka E2E matrix.

## Test data and isolation

- Service tests own their fixtures and should not depend on execution order.
- Cross-service IDs are UUID references; tests must not reach into another service's database.
- Messaging assertions use stable event IDs and consumer-visible outcomes rather than sleeps alone.
- Credentials in test/Compose/Kubernetes examples are development placeholders, never production
  secrets.

## Known gaps

- Component tests run in jsdom; there is not yet a full browser E2E or automated visual-regression suite.
- No load, soak, chaos, automated accessibility, or multi-node failover suite.
- No production-like payment provider, email/SMS, TLS/Ingress, or cloud deployment tests.
- Kubernetes runtime readiness is a separate manual smoke test; static rendering does not prove that
  every pod becomes ready.
- Opt-in Kafka E2E paths are not part of required pull-request CI because of runner cost and memory.
