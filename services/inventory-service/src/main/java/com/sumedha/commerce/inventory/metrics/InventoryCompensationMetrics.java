package com.sumedha.commerce.inventory.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters for the compensation consumer, on the existing Prometheus registry.
 *
 * <p>Cardinality is fixed by construction: four pre-registered counters, no tags derived from
 * order ids, product ids, event ids or error text.
 */
@Component
public class InventoryCompensationMetrics {

    private final Counter received;
    private final Counter released;
    private final Counter duplicateIgnored;
    private final Counter failed;

    public InventoryCompensationMetrics(MeterRegistry registry) {
        this.received = Counter.builder("inventory.compensation.received")
                .description("Compensation events received from the compensation topic")
                .register(registry);
        this.released = Counter.builder("inventory.compensation.released")
                .description("Compensation events that actually released stock")
                .register(registry);
        this.duplicateIgnored = Counter.builder("inventory.compensation.duplicate.ignored")
                .description("Compensation events ignored because the eventId was already applied")
                .register(registry);
        this.failed = Counter.builder("inventory.compensation.failed")
                .description("Compensation events that could not be applied and were retried or dead-lettered")
                .register(registry);
    }

    public void received() { received.increment(); }
    public void released() { released.increment(); }
    public void duplicateIgnored() { duplicateIgnored.increment(); }
    public void failed() { failed.increment(); }
}
