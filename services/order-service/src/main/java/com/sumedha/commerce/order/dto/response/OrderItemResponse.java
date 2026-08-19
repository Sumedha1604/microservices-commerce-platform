package com.sumedha.commerce.order.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderItemResponse(
        UUID id,
        UUID productId,
        String productName,
        String sku,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal,
        Instant createdAt
) {
}
