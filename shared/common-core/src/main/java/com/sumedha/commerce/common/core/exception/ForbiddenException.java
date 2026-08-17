package com.sumedha.commerce.common.core.exception;

public final class ForbiddenException extends CommerceException {

    private static final String ERROR_CODE = "FORBIDDEN";

    public ForbiddenException(String message) {
        super(message, ERROR_CODE, 403);
    }
}
