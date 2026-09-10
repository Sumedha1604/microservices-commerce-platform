package com.sumedha.commerce.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the operator replay path. {@code sendTimeout} bounds how long a replay request may
 * block waiting for the broker to acknowledge before it is reported as a failure.
 */
@ConfigurationProperties(prefix = "order.dlt.replay")
public record DeadLetterReplayProperties(Duration sendTimeout) {

    public DeadLetterReplayProperties {
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("order.dlt.replay.send-timeout must be positive");
        }
    }
}
