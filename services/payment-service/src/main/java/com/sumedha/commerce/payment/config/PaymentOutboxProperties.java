package com.sumedha.commerce.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "payment.outbox")
public record PaymentOutboxProperties(
        boolean enabled,
        Duration pollInterval,
        int batchSize,
        Duration sendTimeout,
        Duration initialBackoff,
        Duration maxBackoff
) {
    public PaymentOutboxProperties {
        if (batchSize < 1) throw new IllegalArgumentException("payment.outbox.batch-size must be positive");
        if (pollInterval.isNegative() || pollInterval.isZero()) throw new IllegalArgumentException("poll-interval must be positive");
        if (sendTimeout.isNegative() || sendTimeout.isZero()) throw new IllegalArgumentException("send-timeout must be positive");
        if (initialBackoff.isNegative() || initialBackoff.isZero()) throw new IllegalArgumentException("initial-backoff must be positive");
        if (maxBackoff.compareTo(initialBackoff) < 0) throw new IllegalArgumentException("max-backoff must be >= initial-backoff");
    }
}
