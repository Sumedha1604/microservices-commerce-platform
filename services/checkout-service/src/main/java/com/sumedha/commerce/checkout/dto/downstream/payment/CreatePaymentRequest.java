package com.sumedha.commerce.checkout.dto.downstream.payment;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePaymentRequest(
        UUID orderId,
        UUID userId,
        BigDecimal amount,
        String currency
) {
}
