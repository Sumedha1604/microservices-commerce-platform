package com.sumedha.commerce.checkout.exception;

import com.sumedha.commerce.common.core.exception.CommerceException;

public final class DownstreamBadGatewayException extends CommerceException {
    public DownstreamBadGatewayException(String serviceName) {
        super(serviceName + " service failed", "DOWNSTREAM_BAD_GATEWAY", 502);
    }
}
