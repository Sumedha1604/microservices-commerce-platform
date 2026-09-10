package com.sumedha.commerce.order.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters for dead-letter capture and replay, exported through the existing Prometheus registry.
 *
 * <p>Cardinality is fixed by construction: four pre-registered counters, one low-cardinality tag
 * ({@code result=success|failure}) and nothing derived from event ids, order ids, topics or
 * exception messages.
 */
@Component
public class DeadLetterMetrics {

    private final Counter captured;
    private final Counter duplicateIgnored;
    private final Counter replaySucceeded;
    private final Counter replayFailed;

    public DeadLetterMetrics(MeterRegistry registry) {
        this.captured = Counter.builder("order.dlt.captured")
                .description("Dead-letter records captured into durable inspection storage")
                .register(registry);
        this.duplicateIgnored = Counter.builder("order.dlt.duplicate.ignored")
                .description("Dead-letter records redelivered after capture and ignored")
                .register(registry);
        this.replaySucceeded = Counter.builder("order.dlt.replay")
                .description("Dead-letter replay attempts by outcome")
                .tag("result", "success")
                .register(registry);
        this.replayFailed = Counter.builder("order.dlt.replay")
                .description("Dead-letter replay attempts by outcome")
                .tag("result", "failure")
                .register(registry);
    }

    public void captured() {
        captured.increment();
    }

    public void duplicateIgnored() {
        duplicateIgnored.increment();
    }

    public void replaySucceeded() {
        replaySucceeded.increment();
    }

    public void replayFailed() {
        replayFailed.increment();
    }
}
