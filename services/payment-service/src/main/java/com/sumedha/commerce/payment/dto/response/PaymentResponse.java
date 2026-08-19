package com.sumedha.commerce.payment.dto.response;

import com.sumedha.commerce.payment.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID orderId,
        UUID userId,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String provider,
        String providerReference,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
}
