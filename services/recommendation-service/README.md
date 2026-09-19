# Recommendation Service

Deterministic, content-based related-product recommendations from a local catalogue projection. It
consumes `ProductUpserted` and `ProductDeleted` from `product.events.v1` (group
`recommendation-service`) into `recommendation_product` in its own PostgreSQL database, and serves
`GET /api/v1/recommendations/products/{productId}`. It never calls product-service and never reads
another service's database.

- **Scoring:** same category +5, same brand +2, same currency with price within 20% +1. The
  candidate must share category or brand. Order is score, then name, then productId. The response
  includes `score` and `reasons`.
- **Visibility:** only live, `active`, `ACTIVE` candidates; the source is excluded. A missing or
  deleted source is `404`.
- **Idempotency:** a `processed_event` claim plus a `source_version` guard in one transaction.
  Deletes leave tombstones.
- **Failures:** 3 attempts for transient errors; unreadable events go straight to
  `product.events.v1.recommendation.DLT`.
- **No popularity/trending:** the platform has no trustworthy purchase signal yet.
- **No ML** of any kind: no embeddings, models or behavioural data.

See [../../docs/api/recommendation-service.md](../../docs/api/recommendation-service.md),
[../../docs/database/recommendation-service.md](../../docs/database/recommendation-service.md),
[../../docs/events/product-events.md](../../docs/events/product-events.md) and
[ADR 0007](../../docs/decisions/0007-product-recommendations.md).

## Running locally

```
docker compose -f tests/end-to-end/compose.yml -f infrastructure/kafka/compose.kafka.yml \
  up -d kafka product recommendation
```

## Tests

```
mvn -pl services/recommendation-service -am test
```

- Unit: `ProductEventParserTest`, `RelatedProductRankerTest` (every scoring rule),
  `ProductProjectionProcessorTest`, `ProductEventListenerTest`, `RecommendationServiceTest`,
  `RecommendationControllerTest`.
- `RecommendationProjectionPostgresIntegrationTest` (real PostgreSQL): upsert/update/delete,
  duplicates, concurrency, stale events, tombstones, atomicity, rollback, migration.
- `RelatedProductsPostgresIntegrationTest` (real PostgreSQL): documented ordering, exclusions, limit,
  source handling, and a differential check that the SQL ranking equals the Java ranker.
- `RecommendationKafkaIntegrationTest` (embedded Kafka + PostgreSQL): lifecycle over the broker,
  duplicates, stale versions, DLT with key/value/`traceparent`, poison records, bounded retry, trace
  continuation.

## Environment variables

| Variable | Default |
|---|---|
| `RECOMMENDATION_DB_URL` | `jdbc:postgresql://localhost:5432/recommendation_db` |
| `RECOMMENDATION_DB_USERNAME` | `recommendation_user` |
| `RECOMMENDATION_DB_PASSWORD` | `change-me` |
| `RECOMMENDATION_SERVER_PORT` | `8091` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://tempo:4318/v1/traces` |

## Known limitations

- Only products whose events are still retained are known.
- Relations use category and brand ids, not names.
- No FX conversion for prices.
- No DLT replay tooling.
- The API is public.
