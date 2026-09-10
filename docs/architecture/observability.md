# Observability

## Metrics (implemented)

Prometheus is the metrics backend for local development.

- All 9 services expose `/actuator/health` and `/actuator/prometheus` (Micrometer + `micrometer-registry-prometheus`).
- Each service tags its metrics with a stable `application` label (e.g. `application=order-service`), so series can be filtered/grouped per service.
- Prometheus config: [`infrastructure/observability/prometheus.yml`](../../infrastructure/observability/prometheus.yml) — scrapes all 9 services on their container DNS names/ports every 15s.
- Prometheus Compose overlay: [`infrastructure/observability/compose.prometheus.yml`](../../infrastructure/observability/compose.prometheus.yml) — runs Prometheus on port 9090.

### Running it

```bash
docker compose -f tests/end-to-end/compose.yml -f infrastructure/observability/compose.prometheus.yml up -d
```

Prometheus UI: http://localhost:9090

### Monitored applications

`api-gateway` (8080), `auth-service` (8081), `user-service` (8082), `product-service` (8083),
`inventory-service` (8084), `cart-service` (8085), `order-service` (8086), `payment-service` (8087),
`checkout-service` (8088).

### Example queries

- `up` — target availability per service
- `jvm_memory_used_bytes{application="order-service"}` — JVM memory usage
- `http_server_requests_seconds_count` — HTTP request counts (labeled by `application`, `method`, `status`, `uri`, `outcome`; no user/order/payment identifiers)

## Centralized logging (implemented)

Loki stores local Docker container logs and Promtail discovers containers through the Docker socket. Grafana provisions both the Loki and Prometheus datasources automatically.

### Running it

```bash
docker compose \
  -f tests/end-to-end/compose.yml \
  -f infrastructure/observability/compose.prometheus.yml \
  -f infrastructure/observability/compose.logging.yml \
  up -d
```

- Grafana: http://localhost:3000 (default local credentials: `admin` / `admin`)
- Loki: http://localhost:3100
- Prometheus: http://localhost:9090

In Grafana Explore, select **Loki** and query `{service="order"}`. The `service` label is the Docker Compose service name; all application services can be queried the same way (for example, `api-gateway`, `auth`, `user`, `product`, `inventory`, `cart`, `order`, `payment`, and `checkout`). The `container_name` and `stream` labels are also available.

## Tracing across Kafka (implemented)

The transactional outbox deliberately does not persist or reconstruct the original HTTP trace
context. The scheduled publisher therefore starts a new producer trace when it drains an outbox
row. `spring.kafka.template.observation-enabled` makes `KafkaTemplate` inject that producer
trace's `traceparent` header on send, and `spring.kafka.listener.observation-enabled` makes the
listener container continue it on receive. Nothing injects headers by hand.

The resulting traces are:

```
payment-service  SERVER      http post /api/v1/payments/{paymentId}/authorize

payment-service  PRODUCER    payment.events.v1 send
order-service    CONSUMER      payment.events.v1 process
```

The consumer span's parent is the producer span, so the same producer `traceId` appears in the
record's `traceparent` header and the order-service consumer log. It is not the HTTP request's
trace ID. Durable continuation or linking is deferred until it can be implemented with supported
Spring/OpenTelemetry APIs rather than hand-built tracing headers. Because
`logging.pattern.correlation` puts `traceId`/`spanId` in the MDC, the producer/consumer trace can
still be pivoted to its logs in Loki with a plain line filter -
`{service=~"payment|order"} |= "<traceId>"` - and back. `traceId` and `spanId` are deliberately
**not** Loki labels: they are unbounded cardinality and belong in the log body.

### Kafka metrics actually exposed

Only the Micrometer observation metrics are present on `/actuator/prometheus`:

- `spring_kafka_template_seconds{,_max}` and `spring_kafka_template_active_seconds{,_max}` on
  payment-service, tagged `messaging_destination_name="payment.events.v1"`,
  `messaging_operation="publish"`. order-service exposes the same family for its dead-letter
  template (`name="deadLetterKafkaTemplate"`), which is how DLT publishes show up.
- `spring_kafka_listener_seconds{,_max}` and `spring_kafka_listener_active_seconds{,_max}` on
  order-service, tagged `messaging_source_name="payment.events.v1"`,
  `messaging_kafka_consumer_group="order-service"`, and `error` (`none`, or the exception simple
  name for dead-lettered records).

The native Kafka client metrics (`kafka_producer_*`, `kafka_consumer_*`) are **not** exposed:
both services declare their own typed producer/consumer factory beans, which backs off Spring
Boot's `KafkaClientMetrics` binder. Do not write queries or dashboards against those names.

## Dead-letter operations (implemented)

order-service captures every record that reaches `payment.events.v1.DLT` into the
`dead_letter_event` table and exposes it for inspection and controlled replay. Four counters are
published to the existing Prometheus registry:

| Metric | Meaning |
| --- | --- |
| `order_dlt_captured_total` | Dead-letter records captured into inspection storage |
| `order_dlt_duplicate_ignored_total` | Redeliveries ignored because the record was already captured |
| `order_dlt_replay_total{result="success"}` | Replays acknowledged by the broker |
| `order_dlt_replay_total{result="failure"}` | Replay attempts the broker did not acknowledge |

Cardinality is fixed by construction: one low-cardinality tag (`result`), and nothing derived
from event ids, order ids, topics or exception text. `order_dlt_captured_total` rising is the
signal worth alerting on - it means events are being rejected outright.

Capture and replay are logged structurally (record id, `eventId`, `eventType`, `orderId`,
original topic, Kafka coordinates, replay count). Payloads are deliberately never logged; they
are served only by the detail endpoint. See
[../events/dlt-operations.md](../events/dlt-operations.md).

## Inventory compensation (implemented)

When a payment failure cancels an order, order-service queues an `InventoryReleaseRequested`
event in its own transactional outbox and inventory-service consumes it to release the reserved
stock. Seven counters cover the two halves:

| Metric | Meaning |
| --- | --- |
| `order_compensation_persisted_total` | Compensation rows written inside a business transaction |
| `order_compensation_publish_total{result="success"}` | Acknowledged publishes |
| `order_compensation_publish_total{result="failure"}` | Publish attempts the broker did not acknowledge |
| `inventory_compensation_received_total` | Compensation events received |
| `inventory_compensation_released_total` | Events that actually released stock |
| `inventory_compensation_duplicate_ignored_total` | Redeliveries ignored as already applied |
| `inventory_compensation_failed_total` | Events that could not be applied (retried or dead-lettered) |

Cardinality is fixed by construction: one low-cardinality tag (`result`), nothing derived from
order ids, product ids, event ids or error text. In a healthy system
`released + duplicate_ignored` tracks `received`, and `order_compensation_persisted_total` tracks
`order_compensation_publish_total{result="success"}` with only a short lag. A rising
`inventory_compensation_failed_total` is the signal worth alerting on: it means stock is staying
reserved for cancelled orders.

Note that the trace **breaks at the outbox, deliberately**: the payment consumer's span covers the
database transaction, and publishing happens later in a new trace, because trace context is not
stored in the outbox row. Correlate the halves by `orderId` and `eventId`. See
[../events/inventory-compensation.md](../events/inventory-compensation.md).

## Not yet implemented

- Grafana dashboards/visualization
- Alerting rules
