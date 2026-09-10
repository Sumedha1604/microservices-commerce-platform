package com.sumedha.commerce.order.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters for the inventory-compensation outbox, on the existing Prometheus registry.
 *
 * <p>Cardinality is fixed by construction: three pre-registered counters and one
 * low-cardinality {@code result} tag. Nothing is derived from order ids, event ids or error text.
 */
@Component
public class OrderCompensationMetrics {

    private final Counter persisted;
    private final Counter publishSucceeded;
    private final Counter publishFailed;

    public OrderCompensationMetrics(MeterRegistry registry) {
        this.persisted = Counter.builder("order.compensation.persisted")
                .description("Compensation events written to the outbox inside a business transaction")
                .register(registry);
        this.publishSucceeded = Counter.builder("order.compensation.publish")
                .description("Compensation outbox publish attempts by outcome")
                .tag("result", "success")
                .register(registry);
        this.publishFailed = Counter.builder("order.compensation.publish")
                .description("Compensation outbox publish attempts by outcome")
                .tag("result", "failure")
                .register(registry);
    }

    public void persisted() {
        persisted.increment();
    }

    public void publishSucceeded() {
        publishSucceeded.increment();
    }

    public void publishFailed() {
        publishFailed.increment();
    }
}
