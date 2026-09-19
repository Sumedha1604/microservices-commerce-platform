package com.sumedha.commerce.recommendation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Counters and the request timer for recommendation-service.
 *
 * <p>Cardinality is fixed by construction: every meter is pre-registered and the only tag is
 * {@code outcome} with three values. Nothing is derived from product ids, event ids or error text.
 */
@Component
public class RecommendationMetrics {

    public enum Outcome { SUCCESS, NOT_FOUND, REJECTED }

    private final MeterRegistry registry;
    private final Counter eventsReceived;
    private final Counter projectionUpserted;
    private final Counter projectionDeleted;
    private final Counter duplicateIgnored;
    private final Counter staleIgnored;
    private final Counter failed;
    private final Map<Outcome, Counter> requests = new EnumMap<>(Outcome.class);
    private final Timer requestDuration;

    public RecommendationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.eventsReceived = Counter.builder("recommendation.events.received")
                .description("Product events delivered to the recommendation listener (each attempt)")
                .register(registry);
        this.projectionUpserted = Counter.builder("recommendation.projection.upserted")
                .description("ProductUpserted events applied to the recommendation projection")
                .register(registry);
        this.projectionDeleted = Counter.builder("recommendation.projection.deleted")
                .description("ProductDeleted events that tombstoned a product")
                .register(registry);
        this.duplicateIgnored = Counter.builder("recommendation.duplicate.ignored")
                .description("Product events ignored because the eventId was already processed")
                .register(registry);
        this.staleIgnored = Counter.builder("recommendation.stale.ignored")
                .description("Product events ignored because a newer version or the deletion was already applied")
                .register(registry);
        this.failed = Counter.builder("recommendation.failed")
                .description("Product event processing attempts that failed and were retried or dead-lettered")
                .register(registry);
        for (Outcome outcome : Outcome.values()) {
            requests.put(outcome, Counter.builder("recommendation.requests")
                    .description("Related-product recommendation requests by outcome")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
        this.requestDuration = Timer.builder("recommendation.request.duration")
                .description("Time spent computing related-product recommendations")
                .register(registry);
    }

    public void eventReceived() { eventsReceived.increment(); }
    public void projectionUpserted() { projectionUpserted.increment(); }
    public void projectionDeleted() { projectionDeleted.increment(); }
    public void duplicateIgnored() { duplicateIgnored.increment(); }
    public void staleIgnored() { staleIgnored.increment(); }
    public void failed() { failed.increment(); }
    public void requestRejected() { requests.get(Outcome.REJECTED).increment(); }

    public Timer.Sample startRequest() {
        return Timer.start(registry);
    }

    /** Counts the request under {@code outcome} and returns its duration in nanoseconds. */
    public long stopRequest(Timer.Sample sample, Outcome outcome) {
        requests.get(outcome).increment();
        return sample.stop(requestDuration);
    }
}
