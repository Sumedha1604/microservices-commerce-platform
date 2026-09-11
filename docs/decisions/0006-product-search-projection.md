# 6. Product search as an asynchronous PostgreSQL projection

- **Status:** Accepted
- **Date:** 2026-09-11
- **Scope:** `search-service` (new), `product-service`, `shared/common-events`, `api-gateway`, local compose

## Context

Product discovery today is `GET /api/v1/products?search=`, a `lower(name) LIKE %term%` over
product-service's own tables. It matches names only (no description, no SKU), has no relevance
ranking, is unindexed for substring matching, and puts every search on the catalogue's
transactional database.

What inspection found:

- **Product fields:** `productId`, `sku` (unique, immutable), `name`, `slug`, `shortDescription`,
  `description`, `categoryId`, `brandId`, `price` (NUMERIC 19,4), `currency`, `status`
  (`DRAFT`/`ACTIVE`/`INACTIVE`/`DISCONTINUED`), `active`, `createdAt`, `updatedAt`. Category and brand
  names live in their own tables. New products start as `DRAFT`. Deletion is a hard delete, refused
  while images or attributes exist.
- **No events.** product-service had no Kafka dependency and published nothing.
- **No reliable version.** `products` had no version column; `updatedAt` is a wall-clock
  `@PreUpdate` timestamp, and concurrent updates silently overwrote each other.
- **No search engine anywhere in the repository.** No Elasticsearch, OpenSearch or other search
  engine, no `pg_trgm`, no `tsvector`.
- The platform already runs transactional outboxes (payment, order) and idempotent
  `processed_event` consumers with consumer-owned DLTs (order, inventory, notification).

## Decision

**A dedicated search-service owns a denormalized search read model, kept in sync asynchronously
from product lifecycle events.** It never calls product-service while handling an event or a search.

**Search technology: PostgreSQL.** The model is a single `product_search_document` table with:

- a generated, weighted `tsvector` (`name` and `sku` weight A, `short_description` B,
  `description` C) under a GIN index, for word matching with English stemming and ranking;
- a generated lower-cased `search_text` column under a `pg_trgm` GIN index, for case-insensitive
  partial matching (`lapt` → *Laptop*, `martph` → *smartphone*, `PH-10` → SKU `PH-100`).

Relevance is an explicit, explainable score: exact name/SKU match, name prefix, name substring,
`ts_rank` and trigram similarity of the name. Elasticsearch/OpenSearch was not added. The
repository has none, the catalogue is small, and PostgreSQL satisfies every requirement of this
milestone (partial match, weighted ranking, filters, pagination) with indexes. A second datastore
technology would add an operational burden this milestone does not justify.

**Event contract: projection-style `ProductUpserted` / `ProductDeleted`** on `product.events.v1`,
keyed by `productId`, in the shared v1 `EventEnvelope`. `ProductUpserted` carries the complete
current catalogue fields of the `products` row. A consumer replaces its copy wholesale, so a missed
intermediate update can never leave a half-applied state. `ProductCreated`/`ProductUpdated` would
have told the one consumer nothing extra. Category and brand are ids only: their names change
independently in other rows and would need fan-out events to stay correct.

**product-service gets a transactional outbox**, a deliberate port of payment-service's rather than
a shared framework (none exists). Create/update/delete and the outbox row commit in one transaction.
A scheduled publisher claims bounded batches with `FOR UPDATE SKIP LOCKED` under a per-product
ordering guard, marks rows `PUBLISHED` only after the broker acknowledges, and backs off
exponentially on failure. It reuses the stored `eventId` and bytes forever. There is no Kafka call
inside a business transaction.

**product-service gets an optimistic-lock `version`** (Flyway V2, JPA `@Version`). It is the one
reliable ordering signal available: the optimistic lock makes it strictly increasing per committed
change. `ProductUpserted` carries it; `ProductDeleted` carries `lastVersion + 1`. A PUT that changes
nothing issues no UPDATE, keeps its version and emits no event. Concurrent updates now fail with
`409 CONFLICT` instead of losing one writer's change, matching inventory, order and payment.

**The consumer has two independent guards, both inside one transaction:**

1. `processed_event` claim (`insert … on conflict (event_id) do nothing`). The same `eventId`
   delivered again, or concurrently, is a no-op.
2. A version guard inside the projection statement
   (`… on conflict (product_id) do update … where stored.source_version < incoming`). An older state
   under a *different* eventId (a replay, or an overtaken record) changes nothing.

Deletion leaves a **tombstone**: catalogue fields cleared, `deleted = true`, `source_version` kept.
A late or replayed upsert therefore cannot resurrect a deleted product. Product ids are random UUIDs
and never reused, so a tombstone is final.

**Visibility.** Everything is indexed; search returns only live, `active = true` products, with
status `ACTIVE` by default. The `status` filter can select `DRAFT`/`INACTIVE`/`DISCONTINUED`. That
exposes nothing beyond what product-service's public catalogue API already returns. Deactivation is
an ordinary `ProductUpserted` carrying the new `status`/`active`.

**Failure handling matches the other consumers:** 3 attempts with a 1 s backoff for transient
failures, immediate dead-letter for unreadable or invalid events, and a consumer-owned
`product.events.v1.search.DLT` with the original key, value and `traceparent`.

**Bootstrap: Option A.** The consumer group starts from the earliest retained offset and
reconstructs exactly what is retained, and no more. A pull-based rebuild from product-service's API
was rejected. The public list endpoint returns summaries without description, status, active or
version, so a rebuild would need one extra call per product and still could not order itself
against live events.

## Alternatives considered

- **Elasticsearch/OpenSearch.** Better at fuzzy multilingual relevance and huge corpora. It is a new
  cluster to run, secure and back up, for a catalogue PostgreSQL indexes comfortably. Revisit when
  relevance tuning, facets or scale actually demand it.
- **Improve product-service's own search endpoint.** It would couple search load to the
  transactional catalogue database and leave no place for a denormalized model.
- **Synchronous lookups from search to product-service.** This is the runtime coupling the milestone
  exists to avoid.
- **CDC (Debezium) instead of an outbox.** It needs Kafka Connect and a replication slot. The outbox
  already exists as a platform pattern.
- **`updatedAt` as the ordering signal.** It is wall-clock based and not guaranteed monotonic across
  instances or clock adjustments.
- **Delete by removing the row.** A replayed upsert would re-create the product.

## Consequences

- Search is **eventually consistent**: typically the outbox poll interval (1 s) plus consumer lag.
  `indexedAt` is returned so the lag is visible.
- **Delivery is at-least-once plus deduplication, not exactly-once.** An outbox crash window,
  rebalance or operator replay can redeliver, and the two guards absorb it.
- **Ordering is per product** (`productId` key plus the outbox ordering guard). The version guard
  makes the final state correct even when an older state does arrive late.
- product-service now depends on Kafka at runtime for publishing (not for writes). With no broker,
  rows stay `PENDING` and product writes keep working.
- **Products that existed before this milestone produce no events** and are not in search until they
  next change. Once retention expires, a brand-new search-service cannot rebuild the full catalogue.
  See *Future work*.
- Concurrent product updates now return `409` instead of silently losing a write.

## Future work

- **Bootstrap/re-snapshot:** a product-service job that enqueues a `ProductUpserted` (current
  version) for every product through the same outbox. It is idempotent thanks to the version guard.
  Alternatively, a log-compacted product topic that keeps the latest event per `productId`.
- Category and brand names in search (requires events for those entities and fan-out).
- DLT inspection/replay for `product.events.v1.search.DLT`.
- Currency-aware price filtering and sorting (today prices of different currencies compare
  numerically unless `currency` is given).
- Pruning `PUBLISHED` product outbox rows, and `processed_event` retention.
