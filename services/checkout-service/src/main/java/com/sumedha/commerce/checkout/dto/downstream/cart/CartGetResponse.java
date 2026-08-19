package com.sumedha.commerce.checkout.dto.downstream.cart;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CartGetResponse(
        UUID id,
        UUID userId,
        List<CartItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {
}
