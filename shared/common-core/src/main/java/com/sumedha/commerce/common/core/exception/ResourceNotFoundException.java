package com.sumedha.commerce.common.core.exception;

public final class ResourceNotFoundException extends CommerceException {

    private static final String ERROR_CODE = "RESOURCE_NOT_FOUND";

    public ResourceNotFoundException(String message) {
        super(message, ERROR_CODE, 404);
    }
}
