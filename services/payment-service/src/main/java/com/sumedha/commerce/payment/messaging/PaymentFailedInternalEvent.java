package com.sumedha.commerce.payment.messaging;

import java.util.UUID;

/**
 * Internal in-process signal that a payment failed, raised from within the payment
 * transaction. {@code failureReason} is the value already persisted on the entity
 * (sanitized/truncated), not the raw request string.
 */
public record PaymentFailedInternalEvent(
        UUID paymentId,
        UUID orderId,
        UUID userId,
        String failureReason
) {
}
