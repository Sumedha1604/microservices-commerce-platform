package com.sumedha.commerce.common.events.payment;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * A payment moved to {@code FAILED}.
 *
 * <p>Consumed by order-service to cancel the matching order. {@code orderId} is the
 * correlation key; {@code failureReason} is a short human-readable string for logs/audit,
 * not a machine-parsed code.
 *
 * @param paymentId     the failed payment
 * @param orderId       the order this payment belongs to (envelope partition key)
 * @param userId        the owning user
 * @param failureReason short description of why authorization failed
 */
public record PaymentFailedEvent(
        @JsonProperty("paymentId") UUID paymentId,
        @JsonProperty("orderId") UUID orderId,
        @JsonProperty("userId") UUID userId,
        @JsonProperty("failureReason") String failureReason
) {
}
