# 7. Deterministic content-based product recommendations

- **Status:** Accepted
- **Date:** 2026-09-11
- **Scope:** `recommendation-service` (new), `shared/common-events` (one DLT constant), `api-gateway`, local compose

## Context

The platform wants "related products" on a product page and, ideally, "popular" products.
Inspection of what data actually exists:

| Candidate signal | Exists? | Trustworthy for recommendations? |
|---|---|---|
| Product catalogue (`product.events.v1`: `categoryId`, `brandId`, `price`, `currency`, `status`, `active`, `version`) | Yes, with a transactional outbox and a monotonic version | **Yes.** It is the complete, ordered state of every product that changed since product-service started publishing |
| Product views / clicks | No events of any kind | n/a |
| Cart additions (`cart_items`) | Only as cart-service rows, with no events | No. Carts are intent, not purchase, and reading cart-service's database would break data ownership |
| Orders (`order_items`) | Only as order-service rows. order-service publishes only `InventoryReleaseRequested` | Not as-is: see below |
| Payment events (`PaymentAuthorized`/`PaymentFailed`) | Yes | No. They carry no product lines |

On orders specifically: an order becomes `CONFIRMED` when payment is **authorized**, not captured,
and `CONFIRMED -> CANCELLED` remains a valid transition when a later `PaymentFailed` arrives. The
platform does not model fulfilment or completion. A "purchase count" built on confirmation would
have to be decremented on cancellation, and would still count authorizations that never became
money. There is no single factual event that means "this product was bought".

## Decision

**A dedicated recommendation-service with its own PostgreSQL database consumes `product.events.v1`**
(group `recommendation-service`) into a local projection, `recommendation_product`. It never calls
product-service and never reads search-service's database, even though search holds a similar
projection. Each consumer owns its own read model.

**No new event contracts, and no popularity/trending.** No trustworthy purchase signal exists (see
above), so the platform does not pretend to have one: no view events, no cart-as-purchase counting,
and no invented `PurchaseCompleted`.

**Related products are content-based and deterministic.** For a source product:

| Rule | Points |
|---|---|
| same `categoryId` | +5 |
| same `brandId` (source has a brand) | +2 |
| same currency **and** `|candidate.price − source.price| ≤ 20% × source.price` | +1 |

- A candidate must share the category **or** the brand. Price similarity only reorders related
  products; it never makes an unrelated product related.
- The weights encode a strict priority: category alone (5) beats brand + price (3), and brand alone
  (2) beats price alone.
- Candidates are live (not deleted), `active = true` and `status = ACTIVE`. The source itself is
  excluded.
- Order: score descending, then name ascending (binary collation), then `productId` ascending.
  Identical input always produces identical output.
- No FX conversion: prices in different currencies never earn the price bonus.
- Each result carries its `score` and `reasons` (`SAME_CATEGORY`, `SAME_BRAND`, `SIMILAR_PRICE`),
  and the response names the strategy `CONTENT_BASED_V1`.

The ranking runs as one SQL query with the weights bound as parameters, so a large category is never
loaded into memory. `RelatedProductRanker` states the same rules in plain Java. An integration test
proves the SQL returns exactly the Java ranking (ids, scores, reasons) for a seeded catalogue and for
an 80-product random catalogue with mixed-case and duplicate names, NULL brands, two currencies, every
status, deletions and prices on the band edge.

**Source product handling:** a source that is unknown to the projection, or deleted, is `404
RESOURCE_NOT_FOUND`, the platform convention for a resource addressed by id. A live source that is
inactive or not `ACTIVE` still gets recommendations. This is useful for "alternatives to this
discontinued product" and exposes nothing beyond what the public catalogue already shows.

**The projection reuses search-service's proven rules, as a copy, not a shared library:**
`processed_event` claim plus a version-guarded upsert in one transaction, and tombstones on delete
so a replayed older upsert cannot resurrect a product. Each consumer validates only the fields it
uses.

**Failure handling matches every other consumer:** 3 attempts for transient failures, immediate
dead-letter for unreadable or unsupported events, and a consumer-owned
`product.events.v1.recommendation.DLT`.

**Bootstrap:** the group starts at the earliest retained offset and rebuilds only what is retained,
the same honest limitation as search.

## Alternatives considered

- **Embeddings, vector search, LLM recommendations, collaborative filtering.** There is no
  behavioural data to learn from, and the milestone asks for explainable, testable results. Rejected
  for now, not forever.
- **Popularity from order-service's `order_items` via a new `OrderConfirmed` event.** It is not a
  purchase fact (authorization only, and still cancellable). A correct version needs a
  confirmed/cancelled pair of events or a captured-payment/fulfilment fact, plus a second outbox
  stream: a separate milestone.
- **"Frequently added to cart".** It would need new cart events, and cart state is mutable and
  per-user. Out of scope, and easily mistaken for purchases.
- **Querying search-service's projection or database.** Rejected on data ownership: it would couple
  two services' schemas and deployments.
- **Synchronous calls to product-service per request.** This is the fan-out the milestone forbids.
- **Ranking in Java over all candidates.** It is simpler, but loads whole categories per request.
  The Java ranker is kept as the specification instead.

## Consequences

- Recommendations are **eventually consistent** with the catalogue.
- **Delivery is at-least-once plus deduplication**, not exactly-once.
- **Bootstrap is limited to retained events.** Products that existed before product-service
  published events, or whose events expired, are absent (neither source nor candidate) until they
  next change.
- Category and brand **names** are not available; relations use ids only. Two differently-named
  categories with the same meaning are unrelated.
- Price similarity is a single fixed band. There is no currency conversion, and `0.00`-priced
  sources only match other free products on price.
- The API is public, like catalogue reads and search.

## Future work

- **Purchase-based popularity** once the platform models a trustworthy purchase fact (captured
  payment or fulfilled order with cancellation/refund compensation), published through an outbox and
  aggregated per product.
- A product re-snapshot job or compacted product topic, benefiting search and recommendations
  equally.
- DLT inspection/replay for `product.events.v1.recommendation.DLT`.
- Per-currency or FX-aware price bands.
