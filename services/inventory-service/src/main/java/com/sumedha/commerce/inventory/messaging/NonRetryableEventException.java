package com.sumedha.commerce.inventory.messaging;

/**
 * The record can never succeed, no matter how many times it is redelivered: malformed JSON, an
 * unknown event type, an unsupported schema version, a payload missing its order or lines, a
 * product with no inventory row, or a release larger than what is actually reserved.
 *
 * <p>Registered with the container's {@code DefaultErrorHandler} as non-retryable, so it goes
 * straight to the dead-letter topic instead of consuming the retry budget and delaying the
 * compensation queued behind it.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
