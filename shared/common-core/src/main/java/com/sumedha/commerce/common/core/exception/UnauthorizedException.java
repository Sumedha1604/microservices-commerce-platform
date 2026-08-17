package com.sumedha.commerce.common.core.exception;

public final class UnauthorizedException extends CommerceException {

    private static final String ERROR_CODE = "UNAUTHORIZED";

    public UnauthorizedException(String message) {
        super(message, ERROR_CODE, 401);
    }
}
