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

Payment events carry the W3C trace context, so the asynchronous hop does not break a trace.
`spring.kafka.template.observation-enabled` makes `KafkaTemplate` inject a `traceparent` header
on send, and `spring.kafka.listener.observation-enabled` makes the listener container continue
that context on receive. Nothing injects headers by hand.

A single `POST /api/v1/payments/{paymentId}/authorize` produces one connected trace:

```
payment-service  SERVER    http post /api/v1/payments/{paymentId}/authorize
payment-service  PRODUCER    payment.events.v1 send
order-service    CONSUMER      payment.events.v1 process
```

The consumer span's parent is the producer span, and the same `traceId` appears in the
payment-service request log, the record's `traceparent` header, and the order-service consumer
log. Because `logging.pattern.correlation` puts `traceId`/`spanId` in the MDC, a trace can be
pivoted to its logs in Loki with a plain line filter - `{service=~"payment|order"} |= "<traceId>"` -
and back. `traceId` and `spanId` are deliberately **not** Loki labels: they are unbounded
cardinality and belong in the log body.

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

## Not yet implemented

- Grafana dashboards/visualization
- Alerting rules
