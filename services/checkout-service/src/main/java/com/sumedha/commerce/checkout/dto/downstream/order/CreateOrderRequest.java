package com.sumedha.commerce.checkout.dto.downstream.order;

import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(
        UUID userId,
        String currency,
        List<CreateOrderItemRequest> items
) {
}
