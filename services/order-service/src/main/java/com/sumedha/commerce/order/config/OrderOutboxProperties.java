package com.sumedha.commerce.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the compensation outbox publisher.
 *
 * <p>{@code enabled} exists so tests can hold the scheduler still and drive the batch processor
 * by hand; every other value is ordinary operational tuning. The invariants are enforced here so
 * a typo fails at startup rather than turning into a publisher that never retries.
 */
@ConfigurationProperties(prefix = "order.outbox")
public record OrderOutboxProperties(
        boolean enabled,
        Duration pollInterval,
        int batchSize,
        Duration sendTimeout,
        Duration initialBackoff,
        Duration maxBackoff
) {
    public OrderOutboxProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("order.outbox.batch-size must be positive");
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("order.outbox.poll-interval must be positive");
        }
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("order.outbox.send-timeout must be positive");
        }
        if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("order.outbox.initial-backoff must be positive");
        }
        if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("order.outbox.max-backoff must be >= initial-backoff");
        }
    }
}
