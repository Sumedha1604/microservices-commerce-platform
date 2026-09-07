package com.sumedha.commerce.payment.messaging;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Internal in-process signal that a payment was authorized, raised from within the payment
 * transaction. Carries only the already-persisted values the Kafka event needs - never the
 * {@code Payment} entity, so the {@code AFTER_COMMIT} listener touches no detached state.
 */
public record PaymentAuthorizedInternalEvent(
        UUID paymentId,
        UUID orderId,
        UUID userId,
        BigDecimal amount,
        String currency
) {
}
