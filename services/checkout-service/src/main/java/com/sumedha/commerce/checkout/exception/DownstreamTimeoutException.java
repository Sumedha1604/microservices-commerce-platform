package com.sumedha.commerce.checkout.exception;

import com.sumedha.commerce.common.core.exception.CommerceException;

public final class DownstreamTimeoutException extends CommerceException {
    public DownstreamTimeoutException(String serviceName) {
        super(serviceName + " service timed out", "DOWNSTREAM_TIMEOUT", 504);
    }
}
