# Product events and the search projection

Operator guide for `product.events.v1`: what product-service publishes, how search-service turns it
into a search index, and what happens when events are late, duplicated, unreadable or missing.

Design rationale: [../decisions/0006-product-search-projection.md](../decisions/0006-product-search-projection.md).

## Flow

```
product-service  (create / update / delete)
   one transaction:  products row (version++)  +  product_outbox_event row
   outbox publisher (1s poll, SKIP LOCKED, per-product ordering, broker ack -> PUBLISHED)
        |
        v
product.events.v1   key = productId, 3 partitions
        |
        v  group "search-service"
search-service, one transaction:
   processed_event claim (eventId)  +  version-guarded upsert / tombstone
        |                                   \
        v                                    `-> product.events.v1.search.DLT (unreadable / exhausted retries)
GET /api/v1/search/products  (PostgreSQL full-text + trigram)
```

## Topics

| | |
|---|---|
| Topic | `product.events.v1`, declared by product-service |
| Key | `productId` (UTF-8) |
| Partitions / RF | 3 / 1 (local development) |
| Serialization | `StringSerializer`; value is the JSON envelope, no type headers |
| Consumer group | `search-service` |
| Dead-letter topic | `product.events.v1.search.DLT`, declared by search-service |

## Contract (schema version 1)

Envelope: `eventId`, `eventType`, `schemaVersion` (= 1), `occurredAt`, `payload`. The Java records
are in `shared/common-events` (`com.sumedha.commerce.common.events.product`).

### `ProductUpserted`

The complete current catalogue state after a create or a change.

| Field | Type | Notes |
|---|---|---|
| `productId` | UUID | key |
| `sku` | string | unique, immutable |
| `name` | string | |
| `slug` | string | |
| `shortDescription` | string, nullable | |
| `description` | string, nullable | |
| `categoryId` | UUID | id only; category name is not in the event |
| `brandId` | UUID, nullable | id only |
| `price` | decimal | exact, never a float |
| `currency` | string | 3 letters |
| `status` | string | `DRAFT`, `ACTIVE`, `INACTIVE`, `DISCONTINUED` |
| `active` | boolean | product-service's independent active flag |
| `version` | long | optimistic-lock version of the committed row; strictly increasing per product |
| `updatedAt` | instant | product-service's `updated_at` |

```json
{
  "eventId": "6d0c…", "eventType": "ProductUpserted", "schemaVersion": 1,
  "occurredAt": "2026-09-11T10:15:30.123Z",
  "payload": {
    "productId": "240e…", "sku": "PH-100", "name": "Smart Phone X", "slug": "smart-phone-x",
    "shortDescription": null, "description": "Flagship smartphone", "categoryId": "a1b2…",
    "brandId": null, "price": 999.00, "currency": "USD", "status": "ACTIVE", "active": true,
    "version": 1, "updatedAt": "2026-09-11T10:15:30.120Z"
  }
}
```

### `ProductDeleted`

| Field | Type | Notes |
|---|---|---|
| `productId` | UUID | key |
| `version` | long | last committed version + 1, so the deletion orders after every upsert |

### When events are emitted

| Product change | Event |
|---|---|
| `POST /api/v1/products` | `ProductUpserted`, version 0 (status `DRAFT`) |
| `PUT /api/v1/products/{id}` that changes the row | `ProductUpserted`, next version |
| `PUT` with identical values | nothing (no UPDATE, no version change) |
| Deactivation (`status`/`active` change) | `ProductUpserted` carrying the new status/flag |
| `DELETE /api/v1/products/{id}` | `ProductDeleted`, version + 1 |
| Delete refused (images/attributes exist) | nothing |
| Image/attribute/category/brand changes | nothing (not part of the contract) |

## Publishing reliability (product-service)

- The product change and its outbox row commit together, or neither does. There is no Kafka call
  inside the business transaction.
- The publisher claims up to `product.outbox.batch-size` rows per poll with `FOR UPDATE SKIP LOCKED`,
  so concurrent publishers never claim one row.
- **Per-product ordering guard:** a row is claimable only if no earlier `PENDING` row exists for the
  same product. A product in backoff holds back only its own later events.
- A row becomes `PUBLISHED` only after the broker acknowledges (bounded by
  `product.outbox.send-timeout`). On failure it stays `PENDING` with `attempt_count`, `last_error`
  and an exponential `next_attempt_at`. The `eventId` and payload never change.
- **Not exactly-once.** A crash after the acknowledgement but before the `PUBLISHED` commit
  republishes the identical record. search-service deduplicates it.

## Consuming (search-service)

One transaction per record:

1. **Claim** the `eventId` in `processed_event` (`on conflict do nothing`). If it is already
   claimed: `DUPLICATE`, nothing else runs. A concurrent second delivery waits on the first and then
   sees it claimed.
2. **Apply**, with the version guard inside the statement:
   - `ProductUpserted`: insert, or replace **only if** the stored `source_version` is older and the
     document is not a tombstone. Otherwise `STALE_IGNORED`.
   - `ProductDeleted`: turn the document into a tombstone (fields cleared, `deleted = true`) **only
     if** the stored version is older. A delete for a never-seen product creates the tombstone.
3. **Commit.** The Kafka offset is committed afterwards (`AckMode.RECORD`).

A failure anywhere rolls back the claim too, so the retry is processed rather than swallowed.

| Situation | Outcome |
|---|---|
| New product, or newer version | `UPSERTED` |
| Same `eventId` again | `DUPLICATE`, no change |
| Older (or equal) version under another `eventId` | `STALE_IGNORED`, no change, marker recorded |
| Delete of a live product | `DELETED` (tombstone) |
| Upsert after the delete, any version | `STALE_IGNORED` (deletion is final; product ids are never reused) |

## Search semantics

- **Visibility:** a document is returned only if not deleted and `active = true`, and with status
  `ACTIVE` unless the `status` filter asks for another. A new `DRAFT` product is indexed but not
  visible until it is activated.
- **Matching:** case-insensitive; full-text words (English stemming) across name, SKU, short
  description and description, **or** substring match on the same text (trigram-indexed).
- **Relevance (default sort):** exact name/SKU match > name prefix > name contains > full-text rank
  (name/SKU weighted above descriptions) + name similarity. Ties are broken by name, then id.
- See [../api/search-service.md](../api/search-service.md) for parameters.

## Retry and dead-lettering

| Failure | Retried? |
|---|---|
| Malformed JSON, bad envelope, unsupported `schemaVersion`, unknown `eventType` | No: DLT at once |
| Missing/invalid `productId`, `version`, `sku`, `name`, `slug`, `categoryId`, `price`, `currency`, `status`, `active` | No: DLT at once |
| Database unavailable, lock or connection problems, constraint violations | Yes: 3 attempts, 1 s apart, then DLT |
| Duplicate or stale event | Not a failure |

The DLT record keeps the original key, value and `traceparent`, plus Spring Kafka's
`kafka_dlt-original-*` and `kafka_dlt-exception-*` headers. A poison record never stalls the records
behind it on the partition.

**No DLT inspection or replay tooling exists for this topic.** Inspect it by hand:

```bash
docker exec end-to-end-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic product.events.v1.search.DLT \
  --from-beginning --property print.key=true --property print.headers=true
```

Replaying a record onto `product.events.v1` is safe (both guards apply). search-service is the only
consumer of that topic.

## Bootstrap and backfill

**search-service can only index what is still retained on `product.events.v1`.** A new consumer
group starts at the earliest retained offset (`auto.offset.reset=earliest`) and replays forward.
That rebuilds the catalogue **only if every product's latest event is still retained**:

- Products created before product-service had an outbox (before this milestone) have **no events**
  and are not searchable until they next change.
- The local broker uses Kafka's default time-based retention (7 days). After that, a fresh
  search-service will miss products that have not changed since.

This milestone deliberately does not fake a complete reconstruction. Production-capable bootstrap
options, both future work:

1. A product-service re-snapshot job that enqueues `ProductUpserted` for every product through the
   outbox. It is idempotent: an equal version is `STALE_IGNORED`, a missing document is inserted.
2. A log-compacted product topic (`cleanup.policy=compact`, keyed by `productId`), which retains the
   latest event per product indefinitely.

## Tracing

The outbox publisher starts a **new** trace when it sends a row. Trace context is not stored in the
outbox, the same boundary as payment and order. `KafkaTemplate` observation injects `traceparent`,
and the search listener continues it:

```
product-service  PRODUCER  product.events.v1 send       (scheduler thread, new trace)
search-service   CONSUMER    product.events.v1 process  -> "Product event applied to search …"
```

Search HTTP requests produce ordinary server spans. Correlate a product write with its event by
`productId`/`eventId`, which appear in both services' logs.

## Observability

Logs never contain payloads or query text.

| Event | Logger | Fields |
|---|---|---|
| Outbox row persisted | `ProductServiceImpl` | `eventId`, `eventType`, `productId` |
| Publish succeeded / failed | `ProductOutboxBatchProcessor` | `eventId`, `eventType`, `productId`, attempt, topic/partition/offset |
| Event applied | `ProductProjectionProcessor` | `eventId`, `eventType`, `productId`, `version`, outcome |
| Duplicate ignored | `ProductProjectionProcessor` | `eventId`, `eventType`, `productId` |
| Processing attempt failed | `ProductEventListener` | key (productId), exception summary |
| Dead-lettered | `KafkaConsumerConfig` | source coordinates, key, exception |
| Search completed | `ProductSearchService` | query length, filter count, sort, page, size, returned, total, `durationMs` |

Metrics (fixed cardinality):

| Metric | Meaning |
|---|---|
| `search_events_received_total` | deliveries to the listener (each attempt) |
| `search_projection_upserted_total` | upserts applied |
| `search_projection_deleted_total` | products removed from search |
| `search_projection_stale_ignored_total` | older states ignored by the version guard |
| `search_duplicate_ignored_total` | duplicate `eventId`s ignored |
| `search_indexing_failed_total` | failed attempts (retried or dead-lettered) |
| `search_queries_total{outcome="success"\|"rejected"}` | search requests |
| `search_query_duration_seconds_{count,sum,max}` | query execution time |

Healthy: `upserted + deleted + stale_ignored + duplicate_ignored ≈ received`. A rising
`search_indexing_failed_total` means product changes are not reaching search.

## Running it locally

```bash
docker compose -f tests/end-to-end/compose.yml -f infrastructure/kafka/compose.kafka.yml \
  up -d kafka product search

docker exec end-to-end-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group search-service

curl -s 'http://localhost:8090/api/v1/search/products?q=phone'
```

## Known limitations

- Eventual consistency; no read-your-writes guarantee.
- Incomplete bootstrap beyond topic retention, and no events for pre-existing products (see above).
- Category/brand names are not searchable.
- Price filters compare numerically across currencies unless `currency` is given.
- No DLT inspection/replay tooling.
- English-only stemming; no typo tolerance beyond trigram substring matching.
- `product_outbox_event` and `processed_event` are never pruned.
