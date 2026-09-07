package com.sumedha.commerce.common.events.payment;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A payment moved to {@code AUTHORIZED}.
 *
 * <p>Consumed by order-service to confirm the matching order. {@code orderId} is the
 * correlation key; {@code amount}/{@code currency} let a consumer assert the authorised
 * figure without a synchronous call back to payment-service.
 *
 * @param paymentId the authorised payment
 * @param orderId   the order this payment belongs to (envelope partition key)
 * @param userId    the owning user
 * @param amount    authorised amount, exact decimal (money - never a float)
 * @param currency  ISO-4217 code, e.g. {@code "USD"}
 */
public record PaymentAuthorizedEvent(
        @JsonProperty("paymentId") UUID paymentId,
        @JsonProperty("orderId") UUID orderId,
        @JsonProperty("userId") UUID userId,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("currency") String currency
) {
}
