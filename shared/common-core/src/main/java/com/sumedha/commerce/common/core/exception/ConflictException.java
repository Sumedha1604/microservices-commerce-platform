package com.sumedha.commerce.common.core.exception;

public final class ConflictException extends CommerceException {

    private static final String ERROR_CODE = "CONFLICT";

    public ConflictException(String message) {
        super(message, ERROR_CODE, 409);
    }
}
