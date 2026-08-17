package com.sumedha.commerce.common.core.exception;

import java.util.Objects;

public abstract class CommerceException extends RuntimeException {

    private final String errorCode;
    private final int statusCode;

    protected CommerceException(
            String message,
            String errorCode,
            int statusCode
    ) {
        super(Objects.requireNonNull(message, "message must not be null"));
        Objects.requireNonNull(errorCode, "errorCode must not be null");

        if (errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        if (statusCode < 400 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be between 400 and 599");
        }

        this.errorCode = errorCode;
        this.statusCode = statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
