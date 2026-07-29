package com.sumedha.commerce.common.core.exception;

public final class InternalServerException extends CommerceException {

    private static final String ERROR_CODE = "INTERNAL_SERVER_ERROR";

    public InternalServerException(String message) {
        super(message, ERROR_CODE, 500);
    }
}
