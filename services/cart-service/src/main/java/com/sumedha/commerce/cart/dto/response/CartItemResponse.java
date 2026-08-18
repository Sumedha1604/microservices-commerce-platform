package com.sumedha.commerce.cart.dto.response;

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
