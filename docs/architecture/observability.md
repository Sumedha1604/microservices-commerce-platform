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

## Not yet implemented

- Distributed tracing (OpenTelemetry, Jaeger, Zipkin)
- Grafana dashboards/visualization
- Alerting rules
