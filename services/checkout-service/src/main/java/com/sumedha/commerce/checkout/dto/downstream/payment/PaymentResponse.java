package com.sumedha.commerce.checkout.dto.downstream.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        UUID userId,
        String status,
        BigDecimal amount,
        String currency,
        String provider,
        String providerReference,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
}
