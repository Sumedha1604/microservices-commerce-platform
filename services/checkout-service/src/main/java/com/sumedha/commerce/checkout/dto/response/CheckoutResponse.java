package com.sumedha.commerce.checkout.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record CheckoutResponse(
        UUID cartId,
        UUID orderId,
        UUID paymentId,
        String orderStatus,
        String paymentStatus,
        BigDecimal total,
        String currency
) {
}
