# Search Service

Product discovery over a local, denormalized search read model. It consumes `ProductUpserted` and
`ProductDeleted` from `product.events.v1` (group `search-service`), maintains
`product_search_document` in its own PostgreSQL database, and serves `GET /api/v1/search/products`.
It never calls product-service, either to index or to answer a search.

- **Search technology:** PostgreSQL full-text search (weighted generated `tsvector`, GIN) plus
  `pg_trgm` substring matching (GIN). No Elasticsearch or OpenSearch.
- **Consistency:** eventual. Changes appear after product-service's outbox publishes them.
- **Idempotency:** a `processed_event` claim plus a `source_version` guard, in one transaction.
  Deleted products are tombstones.
- **Failures:** 3 attempts for transient errors; unreadable events go straight to
  `product.events.v1.search.DLT`.

See [../../docs/api/search-service.md](../../docs/api/search-service.md),
[../../docs/events/product-events.md](../../docs/events/product-events.md),
[../../docs/database/search-service.md](../../docs/database/search-service.md) and
[ADR 0006](../../docs/decisions/0006-product-search-projection.md).

## Running locally

```
docker compose -f tests/end-to-end/compose.yml -f infrastructure/kafka/compose.kafka.yml \
  up -d kafka product search
```

Or `mvn -pl services/search-service -am spring-boot:run` with `.env.example` values exported.

## Tests

```
mvn -pl services/search-service -am test
```

- Unit: `ProductEventParserTest`, `ProductProjectionProcessorTest`, `ProductEventListenerTest`,
  `ProductSearchServiceTest`, `ProductSearchDocumentRepositoryTest`, `ProductSearchControllerTest`.
- `SearchProjectionPostgresIntegrationTest` (real PostgreSQL): upsert/update/delete, duplicates,
  concurrent duplicates and versions, atomicity, rollback, stale events, tombstones, migration.
- `ProductSearchPostgresIntegrationTest` (real PostgreSQL): name/description/SKU/partial/case-insensitive
  matching, relevance, visibility, filters, pagination, sorting, special characters.
- `ProductSearchKafkaIntegrationTest` (embedded Kafka + PostgreSQL): lifecycle over the broker,
  duplicates, DLT with key/value/`traceparent`, poison records, bounded retry, trace continuation.

## Environment variables

| Variable | Default |
|---|---|
| `SEARCH_DB_URL` | `jdbc:postgresql://localhost:5432/search_db` |
| `SEARCH_DB_USERNAME` | `search_user` |
| `SEARCH_DB_PASSWORD` | `change-me` |
| `SEARCH_SERVER_PORT` | `8090` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://tempo:4318/v1/traces` |

## Known limitations

A new consumer group only indexes events still retained on the topic, and products created before
product-service published events are absent until they change. There are no category/brand names,
no DLT replay tooling, and no authorization (the API is public like the catalogue). See the events doc.
