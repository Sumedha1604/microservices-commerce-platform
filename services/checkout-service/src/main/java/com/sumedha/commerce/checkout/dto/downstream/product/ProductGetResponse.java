package com.sumedha.commerce.checkout.dto.downstream.product;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductGetResponse(
        UUID productId,
        String sku,
        String name,
        BigDecimal price,
        String currency,
        String status,
        boolean active
) {
}
