package com.sumedha.commerce.common.core.api;

import com.sumedha.commerce.common.core.exception.CommerceException;

import java.time.Instant;
import java.util.Objects;

public final class ErrorResponse {

    private final String errorCode;
    private final String message;
    private final int statusCode;
    private final Instant timestamp;

    private ErrorResponse(
            String errorCode,
            String message,
            int statusCode,
            Instant timestamp
    ) {
        this.errorCode = errorCode;
        this.message = message;
        this.statusCode = statusCode;
        this.timestamp = timestamp;
    }

    public static ErrorResponse of(
            String errorCode,
            String message,
            int statusCode
    ) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        Objects.requireNonNull(message, "message must not be null");

        if (errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        if (statusCode < 400 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be between 400 and 599");
        }

        return new ErrorResponse(errorCode, message, statusCode, Instant.now());
    }

    public static ErrorResponse from(CommerceException exception) {
        Objects.requireNonNull(exception, "exception must not be null");

        return of(exception.getErrorCode(), exception.getMessage(), exception.getStatusCode());
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
