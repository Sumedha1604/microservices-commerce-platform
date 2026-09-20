package com.sumedha.commerce.checkout.exception;

import com.sumedha.commerce.common.core.exception.CommerceException;

public final class DownstreamUnavailableException extends CommerceException {
    public DownstreamUnavailableException(String serviceName) {
        super(serviceName + " service is unavailable", "DOWNSTREAM_UNAVAILABLE", 503);
    }
}
