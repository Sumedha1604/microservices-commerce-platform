package com.sumedha.commerce.common.core.exception;

public final class BadRequestException extends CommerceException {

    private static final String ERROR_CODE = "BAD_REQUEST";

    public BadRequestException(String message) {
        super(message, ERROR_CODE, 400);
    }
}
