package com.sumedha.commerce.cart.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CartResponse(
        UUID id,
        UUID userId,
        List<CartItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {
}
