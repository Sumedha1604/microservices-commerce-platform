package com.sumedha.commerce.search.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Counters and the query timer for search-service, on the existing Prometheus registry.
 *
 * <p>Cardinality is fixed by construction: every meter is pre-registered and the only tag is
 * {@code outcome} with two values. Nothing is derived from product ids, event ids, query text or
 * error messages.
 */
@Component
public class SearchMetrics {

    private final Counter eventsReceived;
    private final Counter projectionUpserted;
    private final Counter projectionDeleted;
    private final Counter projectionStaleIgnored;
    private final Counter duplicateIgnored;
    private final Counter indexingFailed;
    private final Counter queriesSucceeded;
    private final Counter queriesRejected;
    private final Timer queryDuration;
    private final MeterRegistry registry;

    public SearchMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.eventsReceived = Counter.builder("search.events.received")
                .description("Product events delivered to the search listener (each attempt)")
                .register(registry);
        this.projectionUpserted = Counter.builder("search.projection.upserted")
                .description("ProductUpserted events applied to the search read model")
                .register(registry);
        this.projectionDeleted = Counter.builder("search.projection.deleted")
                .description("ProductDeleted events that removed a product from search")
                .register(registry);
        this.projectionStaleIgnored = Counter.builder("search.projection.stale.ignored")
                .description("Product events ignored because the read model already held a newer version")
                .register(registry);
        this.duplicateIgnored = Counter.builder("search.duplicate.ignored")
                .description("Product events ignored because the eventId was already processed")
                .register(registry);
        this.indexingFailed = Counter.builder("search.indexing.failed")
                .description("Product event processing attempts that failed and were retried or dead-lettered")
                .register(registry);
        this.queriesSucceeded = Counter.builder("search.queries")
                .description("Product search requests by outcome")
                .tag("outcome", "success")
                .register(registry);
        this.queriesRejected = Counter.builder("search.queries")
                .description("Product search requests by outcome")
                .tag("outcome", "rejected")
                .register(registry);
        this.queryDuration = Timer.builder("search.query.duration")
                .description("Time spent executing product search queries against the read model")
                .register(registry);
    }

    public void eventReceived() { eventsReceived.increment(); }
    public void projectionUpserted() { projectionUpserted.increment(); }
    public void projectionDeleted() { projectionDeleted.increment(); }
    public void projectionStaleIgnored() { projectionStaleIgnored.increment(); }
    public void duplicateIgnored() { duplicateIgnored.increment(); }
    public void indexingFailed() { indexingFailed.increment(); }
    public void queryRejected() { queriesRejected.increment(); }

    public Timer.Sample startQuery() {
        return Timer.start(registry);
    }

    /** Records the query as successful and returns its duration in nanoseconds. */
    public long stopQuery(Timer.Sample sample) {
        queriesSucceeded.increment();
        return sample.stop(queryDuration);
    }
}
