package com.sumedha.commerce.order.messaging;

/**
 * The record can never succeed, no matter how many times it is redelivered: malformed JSON, an
 * unknown event type, an unsupported schema version, an unknown order, or a state transition
 * that contradicts the order's current status.
 *
 * <p>Registered with the container's {@code DefaultErrorHandler} as non-retryable, so it goes
 * straight to the dead-letter topic instead of consuming the retry budget.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
