package com.sumedha.commerce.notification.messaging;

/**
 * The record can never succeed, no matter how many times it is redelivered: malformed JSON, an
 * unsupported schema version, an unknown event type, or a payload missing the identifiers a
 * notification needs.
 *
 * <p>Registered with the container's {@code DefaultErrorHandler} as non-retryable, so it goes
 * straight to the dead-letter topic instead of consuming the retry budget and delaying the
 * records queued behind it on the partition.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
