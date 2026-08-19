package com.sumedha.commerce.checkout.dto.downstream.inventory;

import java.time.Instant;
import java.util.UUID;

public record InventoryGetResponse(
        UUID id,
        UUID productId,
        int quantity,
        int reservedQuantity,
        int availableQuantity,
        Instant createdAt,
        Instant updatedAt
) {
}
