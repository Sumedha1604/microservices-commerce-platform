package com.sumedha.commerce.checkout.dto.downstream.cart;

import java.time.Instant;
import java.util.UUID;

public record CartItemResponse(
        UUID id,
        UUID productId,
        int quantity,
        Instant createdAt,
        Instant updatedAt
) {
}
