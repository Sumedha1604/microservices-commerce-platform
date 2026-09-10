# Notification Service

Records a durable notification for each payment outcome. It consumes `PaymentAuthorized` and
`PaymentFailed` from `payment.events.v1` in its own consumer group (`notification-service`), writes
one `notification` row per event, and exposes read-only inspection APIs. Backed by its own
PostgreSQL database and Flyway.

There is **no email/SMS/push provider**. A notification is recorded with `channel = INTERNAL` and
`status = CREATED`, which means recorded, not sent. The recipient is the event's `userId`; no contact
details are stored or looked up, and the service never calls another service while handling an
event.

## API

All responses use the shared `ApiResponse` envelope; errors use `ErrorResponse`.

- `GET /api/v1/notifications/{notificationId}`
- `GET /api/v1/notifications?orderId=&userId=&eventType=&notificationType=&status=&page=0&size=20`
- `GET /api/v1/notifications/order/{orderId}?page=0&size=20`

No write endpoints. Not authorization-protected yet (like the rest of the platform). See
[../../docs/api/notification-service.md](../../docs/api/notification-service.md).

## Messaging

| | |
|---|---|
| Consumes | `payment.events.v1` (`PaymentAuthorized`, `PaymentFailed`, schema v1) |
| Group | `notification-service` |
| Dead-letter topic | `payment.events.v1.notification.DLT` (declared here) |
| Retries | 3 attempts, 1 s apart, for transient failures; none for unreadable records |
| Idempotency | `processed_event` claim (`on conflict do nothing`) + notification insert in one transaction |

See [../../docs/events/notification-events.md](../../docs/events/notification-events.md) and
[ADR 0005](../../docs/decisions/0005-notification-event-consumer.md).

## Running locally

PostgreSQL must be reachable at `NOTIFICATION_DB_URL`, and a broker at `KAFKA_BOOTSTRAP_SERVERS` for
the consumer to do anything. Copy `.env.example`, adjust (do not commit a real `.env`), export, then:

```
mvn -pl services/notification-service -am spring-boot:run
```

With Docker Compose (Kafka is opt-in):

```
docker compose -f tests/end-to-end/compose.yml -f infrastructure/kafka/compose.kafka.yml \
  up -d kafka payment order notification
```

## Tests

```
mvn -pl services/notification-service -am test
```

- `PaymentEventParserTest`, `NotificationFactoryTest`, `NotificationEventProcessorTest`,
  `PaymentEventListenerTest`, `NotificationQueryServiceTest`, `NotificationControllerTest`: unit tests.
- `NotificationPostgresIntegrationTest`: real PostgreSQL (Testcontainers). Covers persistence,
  duplicate and concurrent-duplicate safety, rollback atomicity, migrations, indexes and query paths.
- `NotificationKafkaIntegrationTest`: embedded Kafka + real PostgreSQL. Covers consumption,
  duplicates, DLT with key/value/`traceparent` preserved, poison records, bounded retries, and
  trace continuation.

Docker must be running for the integration tests.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `NOTIFICATION_DB_URL` | `jdbc:postgresql://localhost:5432/notification_db` | Datasource URL |
| `NOTIFICATION_DB_USERNAME` | `notification_user` | Datasource username |
| `NOTIFICATION_DB_PASSWORD` | `change-me` | Datasource password |
| `NOTIFICATION_SERVER_PORT` | `8089` | HTTP port |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Broker |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://tempo:4318/v1/traces` | Trace export |

## Docker

`docker build -f services/notification-service/Dockerfile .` from the repository root (multi-stage,
matching the other services). Compose probes `/actuator/health`.
