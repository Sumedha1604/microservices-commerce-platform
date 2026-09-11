package com.sumedha.commerce.search.messaging;

/**
 * The record can never succeed, no matter how many times it is redelivered: malformed JSON, an
 * unsupported schema version, an unknown event type, or a payload missing or contradicting the
 * fields the search projection needs.
 *
 * <p>Registered with the container's {@code DefaultErrorHandler} as non-retryable, so it goes
 * straight to the dead-letter topic instead of delaying the product changes queued behind it.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
