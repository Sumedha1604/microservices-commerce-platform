package com.sumedha.commerce.checkout.dto.downstream.order;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateOrderItemRequest(
        UUID productId,
        String productName,
        String sku,
        BigDecimal unitPrice,
        Integer quantity
) {
}
